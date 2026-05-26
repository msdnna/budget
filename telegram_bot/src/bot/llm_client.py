"""LLM-driven parsing of free-form text into a structured transaction draft.

Wraps llama.cpp's OpenAI-compatible chat-completions endpoint. We rely on
`response_format=json_schema` to force well-formed JSON — `strict: true`
plus the schema below means the model can't return prose, missing fields
or extra keys (verified against Qwen3.5-9B Q4 in /home/msdnna/LLM).

Validation order intentionally distinct from JSON shape: schema = structure,
the validators below = semantics (does the category actually exist, is the
amount positive, does the date parse). On any semantic failure we raise
`LLMParseError` so the caller can show a user-friendly retry prompt instead
of writing garbage into the budget.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import date as Date
from datetime import datetime

from openai import APIError as OpenAIAPIError
from openai import AsyncOpenAI

from .api_client import CategoryHint, Counterparty, GlossaryItem

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ParsedTransaction:
    type: str  # "income" | "expense"
    amount: float
    category: str
    counterparty: str
    date: Date
    description: str = ""


class LLMParseError(Exception):
    """Raised when the LLM output fails semantic validation. Includes a short
    user-facing reason so handlers can show it directly."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


_JSON_SCHEMA: dict = {
    "name": "transaction",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "type": {"type": "string", "enum": ["income", "expense"]},
            "amount": {"type": "number"},
            "category": {"type": "string"},
            "counterparty": {"type": "string"},
            "date": {
                "type": "string",
                # Pattern is informative for the model — actual date validity
                # is enforced by `datetime.fromisoformat` after parsing.
                "pattern": r"^\d{4}-\d{2}-\d{2}$",
            },
            "description": {"type": "string"},
        },
        "required": ["type", "amount", "category", "counterparty", "date", "description"],
        "additionalProperties": False,
    },
}

# Category hints and counterparty pairs are now sourced from the backend
# (`/api/telegram/context`) — see api_client.TelegramContext. The admin edits
# keywords in the web UI; counterparty pairs are aggregated from the user's
# own transaction history. No more hardcoded heuristics here.

# Few-shot examples — concrete inputs the model historically misclassified.
# Each pair anchors one of three patterns:
#   1. Generic category (Продукты, Транспорт, Кафе) → counterparty = магазин,
#      description = опциональная деталь.
#   2. Shop-as-category (OZON, Wildberries) → counterparty = TOWAR, NOT shop
#      name. Critical: the base model loves to echo the shop into counterparty
#      and shove the actual purchase into description; both examples below
#      train the opposite.
#   3. Transfers / advances — counterparty is the human / company; description
#      is "how" (bank, advance, prepayment), not "what".
_FEW_SHOT_EXAMPLES: list[tuple[str, dict]] = [
    # ── pattern 1: generic category, shop in counterparty
    (
        "продукты магнит 2300",
        {
            "type": "expense",
            "amount": 2300,
            "category": "Продукты",
            "counterparty": "Магнит",
            "date": "{today}",
            "description": "",
        },
    ),
    (
        "вчера ужин в кафе Чайхона 1500",
        {
            "type": "expense",
            "amount": 1500,
            "category": "Кафе",
            "counterparty": "Чайхона",
            "date": "{yesterday}",
            "description": "ужин",
        },
    ),
    # ── pattern 2: shop-as-category, purchase in counterparty
    (
        "заказал сапоги на озоне 4850",
        {
            "type": "expense",
            "amount": 4850,
            "category": "OZON",
            "counterparty": "сапоги",
            "date": "{today}",
            "description": "",
        },
    ),
    (
        "wildberries наушники 2200",
        {
            "type": "expense",
            "amount": 2200,
            "category": "Wildberries",
            "counterparty": "наушники",
            "date": "{today}",
            "description": "",
        },
    ),
    # ── pattern 3: transfers / advances — description = "how", not "what"
    (
        "перевёл маме 3000 на Сбер",
        {
            "type": "expense",
            "amount": 3000,
            "category": "Переводы",
            "counterparty": "маме",
            "date": "{today}",
            "description": "на Сбер",
        },
    ),
    (
        "аванс 25000",
        {
            "type": "income",
            "amount": 25000,
            "category": "Зарплата",
            "counterparty": "",
            "date": "{today}",
            "description": "аванс",
        },
    ),
    (
        "получил от заказчика ООО Драйв предоплату 100000",
        {
            "type": "income",
            "amount": 100000,
            "category": "Фриланс",
            "counterparty": "ООО Драйв",
            "date": "{today}",
            "description": "предоплата",
        },
    ),
    # ── pattern bonus: utilities with measurable detail
    (
        "вода 850 за 5 кубов",
        {
            "type": "expense",
            "amount": 850,
            "category": "Коммуналка",
            "counterparty": "вода",
            "date": "{today}",
            "description": "5 кубов",
        },
    ),
]


def _format_category_hints(cats: list[CategoryHint]) -> list[str]:
    """Render `- "Category" — kw1, kw2, ...` lines, skipping categories that
    don't have keywords. Order preserved from the API response (admin-sorted)."""
    out: list[str] = []
    for c in cats:
        if c.keywords:
            out.append(f'- "{c.name}" — {", ".join(c.keywords)}')
    return out


def _format_counterparty_lines(cps: list[Counterparty]) -> list[str]:
    """Compact `- "Магнит" → Продукты (15)` lines. Frequency hint helps the
    model bias toward the more-used mapping when a counterparty has appeared
    against multiple categories historically."""
    out: list[str] = []
    for cp in cps:
        if cp.counterparty and cp.category:
            out.append(f'- "{cp.counterparty}" → {cp.category} ({cp.count})')
    return out


def _format_glossary_lines(items: list[GlossaryItem]) -> list[str]:
    out: list[str] = []
    for g in items:
        if g.term and g.meaning:
            out.append(f'- "{g.term}" = {g.meaning}')
    return out


def _system_prompt(
    today: Date,
    expense: list[CategoryHint],
    income: list[CategoryHint],
    glossary: list[GlossaryItem],
    counterparties: list[Counterparty],
) -> str:
    """Builds the per-request system prompt from the live DB context.

    Sections, in priority order for the model:
      1. Today's date (for relative date parsing).
      2. Allowed category lists (hard constraint).
      3. Per-category keyword hints (admin-curated, soft bias).
      4. Glossary / aliases (family-wide).
      5. Counterparty → category map from the user's own history (per-user
         soft bias; trumps generic hints when an exact counterparty match is
         found).
      6. Output rules.
    """
    blocks: list[str] = []

    blocks.append(
        "Ты — парсер сообщений семейного бюджета. Извлекай из текста "
        "пользователя данные о доходе или расходе и возвращай ТОЛЬКО JSON "
        "одним объектом (не массивом)."
    )
    blocks.append(f"Сегодня: {today.isoformat()}.")

    expense_names = [c.name for c in expense]
    income_names = [c.name for c in income]
    blocks.append(
        f"Категории расходов (выбирать только из этого списка): "
        f"{', '.join(expense_names) or '—'}."
    )
    blocks.append(
        f"Категории доходов (выбирать только из этого списка): "
        f"{', '.join(income_names) or '—'}."
    )

    hint_lines = _format_category_hints(expense + income)
    if hint_lines:
        blocks.append(
            'Подсказки по категориям (ориентируйся на них, прежде чем падать в "Прочее"):\n'
            + "\n".join(hint_lines)
        )

    gloss_lines = _format_glossary_lines(glossary)
    if gloss_lines:
        blocks.append(
            "Глоссарий (общие псевдонимы; раскрывай по тексту):\n" + "\n".join(gloss_lines)
        )

    cp_lines = _format_counterparty_lines(counterparties)
    if cp_lines:
        blocks.append(
            "Известные привязки из истории пользователя (число — сколько раз эта "
            "пара уже встречалась; если в тексте найден один из этих контрагентов "
            "— ставь соответствующую категорию):\n" + "\n".join(cp_lines)
        )

    blocks.append(
        "Правила:\n"
        '- type: "income" если это поступление денег (зарплата, аванс, премия, '
        'подарок, кэшбэк, продажа, оплата от заказчика), иначе "expense".\n'
        "- amount: положительное число в рублях, без валют.\n"
        "- category: ровно одно из перечисленных значений (точно, как написано). "
        'Используй "Прочее" ТОЛЬКО если ни одна категория, ни одна подсказка и '
        "ни одна привязка не подходит.\n"
        "- counterparty: ГЛАВНАЯ отличительная информация операции, то, что "
        "категория ещё НЕ сказала.\n"
        "  • Если категория общая (например «Продукты», «Транспорт», «Кафе») — "
        "counterparty = магазин / место / получатель (Магнит, Пятёрочка, такси, "
        "ресторан «Х»).\n"
        "  • Если категория уже описывает магазин или сервис (например «OZON», "
        "«Wildberries», «Apple») — counterparty = что было куплено (сапоги, "
        "плуг, наушники), а НЕ повтор названия магазина.\n"
        "  • Для «Переводы» counterparty = имя/прозвище получателя.\n"
        "  • Если в тексте ничего такого нет — пустая строка.\n"
        "- description: ВТОРОСТЕПЕННАЯ деталь — то, что не вошло в counterparty "
        "и не дублирует категорию: способ оплаты («наличными»), банк перевода "
        '("на Сбер"), количество («5 кубов»), период («за май»), вид аванса '
        "(«премия», «13-я»), форма поступления («предоплата», «постоплата»). "
        "Если такого нет — пустая строка. НЕ кладите сюда основной предмет "
        "покупки — он должен идти в counterparty.\n"
        '- date: YYYY-MM-DD. "сегодня"=сегодня, "вчера"=минус 1 день, '
        '"позавчера"=минус 2 дня. Если дата не указана — сегодня.'
    )

    return "\n\n".join(blocks)


def _few_shot_messages(today: Date) -> list[dict]:
    """Render the static few-shot pairs with today's date substituted in.

    Few-shot anchors the model on Russian financial idioms that vanilla Qwen
    routinely misroutes into "Прочее" (transfers, advances, freelance
    pre-payments). Examples that reference categories the user doesn't have
    still help — the model learns the *mapping pattern*, then the strict
    schema + Python validator clamps the output to the allowed list.
    """
    yesterday = today.fromordinal(today.toordinal() - 1).isoformat()
    today_iso = today.isoformat()
    out: list[dict] = []
    for user_text, json_obj in _FEW_SHOT_EXAMPLES:
        obj = {
            k: (v.format(today=today_iso, yesterday=yesterday) if isinstance(v, str) else v)
            for k, v in json_obj.items()
        }
        out.append({"role": "user", "content": user_text})
        out.append({"role": "assistant", "content": json.dumps(obj, ensure_ascii=False)})
    return out


def _resolve_category(value: str, allowed: list[str]) -> str:
    """Best-effort case-insensitive match to the user's category list.

    Falls back to "Прочее" / "Other" if present — the budget seeds both for
    new installs, and admin renames are rare. Returns the raw value untouched
    when no match and no fallback (let the validator decide).
    """
    if not allowed:
        return value
    by_lower = {c.lower(): c for c in allowed}
    if value.lower() in by_lower:
        return by_lower[value.lower()]
    for fb in ("Прочее", "Other", "прочее"):
        if fb.lower() in by_lower:
            return by_lower[fb.lower()]
    return value


async def parse_transaction(
    client: AsyncOpenAI,
    *,
    model: str,
    text: str,
    today: Date,
    expense: list[CategoryHint],
    income: list[CategoryHint],
    glossary: list[GlossaryItem],
    counterparties: list[Counterparty],
) -> ParsedTransaction:
    """Run one chat-completion with strict JSON output and validate semantics.

    All four context blocks are sourced from `/api/telegram/context`; this
    function is pure given that input (modulo the LLM call). Validators below
    operate on category *names* only — keywords/hints are prompt-level.

    Raises LLMParseError with a short Russian reason on any failure.
    """
    expense_categories = [c.name for c in expense]
    income_categories = [c.name for c in income]
    sys = _system_prompt(today, expense, income, glossary, counterparties)
    messages: list[dict] = [{"role": "system", "content": sys}]
    messages.extend(_few_shot_messages(today))
    messages.append({"role": "user", "content": text})
    try:
        resp = await client.chat.completions.create(
            model=model,
            messages=messages,  # type: ignore[arg-type]
            response_format={"type": "json_schema", "json_schema": _JSON_SCHEMA},  # type: ignore[arg-type]
            temperature=0.1,
            max_tokens=300,
        )
    except OpenAIAPIError as e:
        logger.exception("LLM request failed: %s", e)
        raise LLMParseError("LLM недоступен, попробуйте ещё раз через минуту.") from e

    content = (resp.choices[0].message.content or "").strip()
    if not content:
        raise LLMParseError("LLM вернул пустой ответ.")
    try:
        data = json.loads(content)
    except json.JSONDecodeError as e:
        logger.warning("LLM emitted invalid JSON: %r", content)
        raise LLMParseError("Не удалось разобрать ответ LLM.") from e

    # llama.cpp's strict-json mode isn't always strict: Qwen sometimes wraps
    # the object in a single-element list (especially when the user enumerates
    # several items in one message). Unwrap once; refuse multi-item payloads
    # to avoid silently dropping legitimate compound input.
    if isinstance(data, list):
        if len(data) == 1 and isinstance(data[0], dict):
            data = data[0]
        elif len(data) > 1:
            logger.info("LLM returned multi-item list: %r", content)
            raise LLMParseError(
                "Похоже, в одном сообщении несколько операций. Пришлите по одной."
            )
        else:
            logger.warning("LLM returned unexpected list shape: %r", content)
            raise LLMParseError("Не удалось разобрать ответ LLM.")
    if not isinstance(data, dict):
        logger.warning("LLM returned non-object payload: %r", content)
        raise LLMParseError("Не удалось разобрать ответ LLM.")

    tx_type = data.get("type")
    if tx_type not in ("income", "expense"):
        raise LLMParseError("Не понял, доход это или расход.")

    try:
        amount = float(data.get("amount", 0))
    except (TypeError, ValueError) as e:
        raise LLMParseError("Не понял сумму.") from e
    if amount <= 0:
        raise LLMParseError("Сумма должна быть положительной.")

    try:
        parsed_date = datetime.strptime(data.get("date", ""), "%Y-%m-%d").date()
    except ValueError as e:
        raise LLMParseError("Не понял дату.") from e

    allowed = expense_categories if tx_type == "expense" else income_categories
    category = _resolve_category(str(data.get("category") or "").strip(), allowed)
    # If the user has zero categories of this type — odd edge case, but don't
    # block: the backend rejects empty category at create-time and we'll
    # surface that 400.
    if allowed and category not in allowed:
        raise LLMParseError(
            f'Категория "{category}" не существует. Доступные: {", ".join(allowed)}.'
        )

    counterparty = str(data.get("counterparty") or "").strip()
    description = str(data.get("description") or "").strip()

    return ParsedTransaction(
        type=tx_type,
        amount=amount,
        category=category,
        counterparty=counterparty,
        date=parsed_date,
        description=description,
    )


def make_llm_client(base_url: str, api_key: str) -> AsyncOpenAI:
    """Construct the OpenAI client pointing at the configured endpoint.

    `api_key` is required by the SDK even when the server (e.g. llama.cpp)
    ignores it — we default to a sentinel "not-needed" string. httpx
    underneath honours NO_PROXY so the intra-stack route to host.docker.internal
    stays direct.
    """
    return AsyncOpenAI(base_url=base_url, api_key=api_key or "not-needed")
