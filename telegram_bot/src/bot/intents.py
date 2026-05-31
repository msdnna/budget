"""Intent classification — stage 1 of the two-stage parsing pipeline.

A free-form message can mean several things beyond a plain transaction:
the user may want to add a wishlist item, log a recurring-payment, link an
existing expense, or open a detail-request. We classify first (cheap, tight
schema), then dispatch to a specialized extractor per intent (stage 2 lives
in llm_client / the handlers).

Why a separate classifier instead of one giant schema: the per-intent payloads
diverge a lot (wishlist needs name+price+notes; link needs two descriptors;
detail-request needs an assignee). A single strict schema with every field
"required" trains the model to emit noise; a focused classifier + focused
extractors keeps each prompt legible to a small local model (Qwen Q4).

Trigger phrases are fully DB-driven: the backend seeds a built-in baseline into
the `intent_triggers` collection (models.DefaultIntentPhrases) and serves the
admin-edited result via `/api/telegram/context`. The bot holds NO hardcoded
phrase list — it uses whatever the context delivers (empty is fine; the
classifier still has the prose intent descriptions to lean on).
`template_income` is intentionally NOT an intent here — template-cloning is a
category-driven sub-path of `transaction` (income), decided after parsing, not
from surface phrasing.
"""

from __future__ import annotations

import json
import logging

from openai import APIError as OpenAIAPIError
from openai import AsyncOpenAI

logger = logging.getLogger(__name__)

# Intent constants. `TRANSACTION` is the fallback for anything that reads as a
# plain income/expense entry.
INTENT_TRANSACTION = "transaction"
INTENT_WISHLIST = "wishlist"
INTENT_RECURRING = "recurring_payment"
INTENT_LINK = "link_existing"
INTENT_DETAIL_REQUEST = "detail_request"

ALL_INTENTS = [
    INTENT_TRANSACTION,
    INTENT_WISHLIST,
    INTENT_RECURRING,
    INTENT_LINK,
    INTENT_DETAIL_REQUEST,
]

_INTENT_SCHEMA: dict = {
    "name": "intent",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {"intent": {"type": "string", "enum": ALL_INTENTS}},
        "required": ["intent"],
        "additionalProperties": False,
    },
}


def _classifier_prompt(triggers: dict[str, list[str]]) -> str:
    lines = [
        "Ты — классификатор намерения сообщения в боте семейного бюджета. "
        "Определи, что хочет пользователь, и верни ТОЛЬКО JSON {\"intent\": \"...\"}.",
        "",
        "Возможные намерения:",
        f'- "{INTENT_TRANSACTION}" — обычная запись дохода или расхода '
        "(в т.ч. зарплата, продукты, такси, перевод). Это значение по умолчанию, "
        "если ничего из ниже не подходит.",
        f'- "{INTENT_WISHLIST}" — пользователь хочет/планирует ЧТО-ТО КУПИТЬ в '
        "будущем (желаемая покупка), деньги ещё не потрачены.",
        f'- "{INTENT_RECURRING}" — оплата периодического/коммунального счёта '
        "(ЖКХ, вода, свет, связь, страховка, абонентская плата).",
        f'- "{INTENT_LINK}" — просьба ПРИВЯЗАТЬ уже существующую запись расхода '
        "к регулярному расходу или желаемой покупке.",
        f'- "{INTENT_DETAIL_REQUEST}" — просьба СОЗДАТЬ запрос на детализацию '
        "(ЗнД) и назначить его на члена семьи.",
    ]
    hint_lines: list[str] = []
    for intent in (INTENT_WISHLIST, INTENT_RECURRING, INTENT_LINK, INTENT_DETAIL_REQUEST):
        phrases = triggers.get(intent, [])
        if phrases:
            hint_lines.append(f'- {intent}: {", ".join(phrases)}')
    if hint_lines:
        lines.append("")
        lines.append("Слова/фразы-подсказки (если встречаются — сильный сигнал):")
        lines.extend(hint_lines)
    lines.append("")
    lines.append(
        "Если сообщение похоже на простую трату/доход без этих маркеров — "
        f'верни "{INTENT_TRANSACTION}".'
    )
    return "\n".join(lines)


_FEW_SHOT: list[tuple[str, str]] = [
    ("продукты магнит 2300", INTENT_TRANSACTION),
    ("пришла зарплата за май 50000", INTENT_TRANSACTION),
    ("вчера такси 450 наличными", INTENT_TRANSACTION),
    ("хочу купить робот-пылесос Xiaomi в DNS за 25000", INTENT_WISHLIST),
    ("планирую купить велосипед за 40000", INTENT_WISHLIST),
    ("оплатил счёт за воду, май, 10 кубов, 700 руб", INTENT_RECURRING),
    ("заплатил за интернет 600", INTENT_RECURRING),
    (
        "привяжи расход Откачка от 27.05 к регулярному расходу Откачка из категории Жилье/ЖКХ",
        INTENT_LINK,
    ),
    ("создай ЗнД на Иру на 5000 категория Продукты", INTENT_DETAIL_REQUEST),
]


async def classify_intent(
    client: AsyncOpenAI,
    *,
    model: str,
    text: str,
    triggers: dict[str, list[str]],
) -> str:
    """Return one of ALL_INTENTS. Falls back to TRANSACTION on any failure —
    a misclassification toward the most common path is the safe default
    (the user still gets a confirm screen before anything is written)."""
    messages: list[dict] = [{"role": "system", "content": _classifier_prompt(triggers)}]
    for user_text, intent in _FEW_SHOT:
        messages.append({"role": "user", "content": user_text})
        messages.append({"role": "assistant", "content": json.dumps({"intent": intent})})
    messages.append({"role": "user", "content": text})

    try:
        resp = await client.chat.completions.create(
            model=model,
            messages=messages,  # type: ignore[arg-type]
            response_format={"type": "json_schema", "json_schema": _INTENT_SCHEMA},  # type: ignore[arg-type]
            temperature=0.0,
            max_tokens=20,
        )
    except OpenAIAPIError:
        logger.exception("intent classification request failed")
        return INTENT_TRANSACTION

    content = (resp.choices[0].message.content or "").strip()
    try:
        data = json.loads(content)
        intent = str(data.get("intent", "")).strip()
    except (json.JSONDecodeError, AttributeError):
        logger.warning("intent classifier emitted non-JSON: %r", content)
        return INTENT_TRANSACTION
    return intent if intent in ALL_INTENTS else INTENT_TRANSACTION
