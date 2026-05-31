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
    deposit: str = "bank"  # "bank" | "cash"
    # Set when this expense fulfills a recurring-payment (wishlist) item. Carries
    # through the confirm flow into POST /transactions so forecast marks the
    # item "paid this period". Display name kept separately for the confirm UI.
    wishlist_id: str = ""
    wishlist_name: str = ""


@dataclass(frozen=True)
class ParsedWishlist:
    """A planned purchase extracted from «хочу купить …». `notes` carries the
    place/shop detail (e.g. "DNS"); category is resolved against the user's
    wishlist-section categories."""

    name: str
    estimated_cost: float
    category: str
    notes: str = ""


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
            "deposit": {"type": "string", "enum": ["bank", "cash"]},
        },
        "required": [
            "type",
            "amount",
            "category",
            "counterparty",
            "date",
            "description",
            "deposit",
        ],
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
    # ── pattern: deposit scope — "наличными" / "налом" → cash, else bank.
    # The payment-method word goes to `deposit`, NOT description.
    (
        "продукты пятёрочка 1200 наличными",
        {
            "type": "expense",
            "amount": 1200,
            "category": "Продукты",
            "counterparty": "Пятёрочка",
            "date": "{today}",
            "description": "",
            "deposit": "cash",
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
        '"позавчера"=минус 2 дня. Если дата не указана — сегодня.\n'
        '- deposit: счёт списания/зачисления. "cash" если в тексте есть '
        '«наличными», «налом», «наличка», «кэшем», «cash»; во всех остальных '
        'случаях (в т.ч. если способ оплаты не указан, или сказано «картой», '
        '«по карте», «переводом», «безналом») — "bank". Слово-маркер способа '
        'оплаты идёт в deposit, НЕ в description.'
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
        # Keep every rendered example schema-complete — the model learns the
        # default ("bank") from the bulk of examples and the cash override from
        # the dedicated one.
        obj.setdefault("deposit", "bank")
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

    # Deposit scope — clamp to the two known values; anything unexpected
    # (model hallucination, absent field) defaults to bank, matching the
    # backend's NormalizeDeposit.
    deposit = str(data.get("deposit") or "bank").strip().lower()
    if deposit not in ("bank", "cash"):
        deposit = "bank"

    return ParsedTransaction(
        type=tx_type,
        amount=amount,
        category=category,
        counterparty=counterparty,
        date=parsed_date,
        description=description,
        deposit=deposit,
    )


# ─── Wishlist extraction ────────────────────────────────────────────────────

_WISHLIST_SCHEMA: dict = {
    "name": "wishlist_item",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "estimated_cost": {"type": "number"},
            "category": {"type": "string"},
            "notes": {"type": "string"},
        },
        "required": ["name", "estimated_cost", "category", "notes"],
        "additionalProperties": False,
    },
}


async def parse_wishlist(
    client: AsyncOpenAI,
    *,
    model: str,
    text: str,
    wishlist_categories: list[str],
) -> ParsedWishlist:
    """Extract a planned purchase. `name` = what to buy (without «хочу купить»),
    `estimated_cost` = price, `notes` = where/shop/extra, `category` ∈ the
    user's wishlist-section categories (fallback «Прочее»)."""
    cats_line = ", ".join(wishlist_categories) or "—"
    sys = (
        "Ты извлекаешь планируемую покупку из сообщения пользователя и "
        "возвращаешь ТОЛЬКО JSON.\n"
        "- name: ЧТО хочет купить, без слов «хочу/планирую купить» (например "
        '"робот-пылесос Xiaomi").\n'
        "- estimated_cost: цена в рублях, положительное число.\n"
        "- notes: где купить / магазин / доп. деталь (например \"DNS\"). Если "
        "нет — пустая строка.\n"
        f"- category: ровно одно из списка категорий желаний: {cats_line}. "
        'Если не подходит — "Прочее".'
    )
    messages: list[dict] = [
        {"role": "system", "content": sys},
        {"role": "user", "content": "хочу купить робот пылесос Xiaomi в DNS за 25000 руб"},
        {
            "role": "assistant",
            "content": json.dumps(
                {
                    "name": "робот-пылесос Xiaomi",
                    "estimated_cost": 25000,
                    "category": "Прочее",
                    "notes": "DNS",
                },
                ensure_ascii=False,
            ),
        },
        {"role": "user", "content": text},
    ]
    try:
        resp = await client.chat.completions.create(
            model=model,
            messages=messages,  # type: ignore[arg-type]
            response_format={"type": "json_schema", "json_schema": _WISHLIST_SCHEMA},  # type: ignore[arg-type]
            temperature=0.1,
            max_tokens=200,
        )
    except OpenAIAPIError as e:
        raise LLMParseError("LLM недоступен, попробуйте ещё раз через минуту.") from e

    content = (resp.choices[0].message.content or "").strip()
    try:
        data = json.loads(content)
    except json.JSONDecodeError as e:
        raise LLMParseError("Не удалось разобрать ответ LLM.") from e
    if isinstance(data, list) and len(data) == 1 and isinstance(data[0], dict):
        data = data[0]
    if not isinstance(data, dict):
        raise LLMParseError("Не удалось разобрать ответ LLM.")

    name = str(data.get("name") or "").strip()
    if not name:
        raise LLMParseError("Не понял, что нужно купить.")
    try:
        cost = float(data.get("estimated_cost", 0))
    except (TypeError, ValueError) as e:
        raise LLMParseError("Не понял цену.") from e
    if cost <= 0:
        raise LLMParseError("Цена должна быть положительной.")
    category = _resolve_category(str(data.get("category") or "").strip(), wishlist_categories)
    notes = str(data.get("notes") or "").strip()
    return ParsedWishlist(name=name, estimated_cost=cost, category=category, notes=notes)


# ─── Recurring-payment extraction ────────────────────────────────────────────


@dataclass(frozen=True)
class ParsedRecurring:
    """Raw extraction for a recurring-payment message. `item_name` is the
    model's best guess at which regular item this pays (matched in Python
    against the actual list); empty when nothing fit."""

    item_name: str
    amount: float
    date: Date
    description: str
    deposit: str


_RECURRING_SCHEMA: dict = {
    "name": "recurring_payment",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "item_name": {"type": "string"},
            "amount": {"type": "number"},
            "date": {"type": "string", "pattern": r"^\d{4}-\d{2}-\d{2}$"},
            "description": {"type": "string"},
            "deposit": {"type": "string", "enum": ["bank", "cash"]},
        },
        "required": ["item_name", "amount", "date", "description", "deposit"],
        "additionalProperties": False,
    },
}


async def parse_recurring(
    client: AsyncOpenAI,
    *,
    model: str,
    text: str,
    today: Date,
    regular_items: list[tuple[str, str]],
) -> ParsedRecurring:
    """Extract a recurring-payment. `regular_items` is a list of
    (name, category) for the user's regular расходы — the model picks the best
    `item_name` from those, or "" if none matches.

    `description` collects the numeric/period detail (e.g. "май, 10 кубов");
    `amount`/`date`/`deposit` mirror the transaction parser's semantics."""
    items_lines = "\n".join(f'- "{n}" (категория {c})' for n, c in regular_items) or "— (список пуст)"
    sys = (
        "Ты разбираешь сообщение об оплате периодического/коммунального счёта "
        "и возвращаешь ТОЛЬКО JSON.\n"
        f"Сегодня: {today.isoformat()}.\n\n"
        "Список регулярных расходов пользователя:\n"
        f"{items_lines}\n\n"
        "- item_name: ВЫБЕРИ из списка выше тот регулярный расход, который "
        "оплачивает это сообщение (по смыслу/теме: вода→ЖКХ Вода, интернет→Связь "
        "и т.п.). Если ни один не подходит — пустая строка.\n"
        "- amount: сумма в рублях, положительное число.\n"
        '- date: YYYY-MM-DD; если не указана — сегодня.\n'
        "- description: период и измеримые детали (например \"май, 10 кубов\"). "
        "Если нет — пустая строка.\n"
        '- deposit: "cash" если сказано наличными/налом, иначе "bank".'
    )
    messages: list[dict] = [{"role": "system", "content": sys}, {"role": "user", "content": text}]
    try:
        resp = await client.chat.completions.create(
            model=model,
            messages=messages,  # type: ignore[arg-type]
            response_format={"type": "json_schema", "json_schema": _RECURRING_SCHEMA},  # type: ignore[arg-type]
            temperature=0.1,
            max_tokens=200,
        )
    except OpenAIAPIError as e:
        raise LLMParseError("LLM недоступен, попробуйте ещё раз через минуту.") from e

    content = (resp.choices[0].message.content or "").strip()
    try:
        data = json.loads(content)
    except json.JSONDecodeError as e:
        raise LLMParseError("Не удалось разобрать ответ LLM.") from e
    if isinstance(data, list) and len(data) == 1 and isinstance(data[0], dict):
        data = data[0]
    if not isinstance(data, dict):
        raise LLMParseError("Не удалось разобрать ответ LLM.")

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
    deposit = str(data.get("deposit") or "bank").strip().lower()
    if deposit not in ("bank", "cash"):
        deposit = "bank"
    return ParsedRecurring(
        item_name=str(data.get("item_name") or "").strip(),
        amount=amount,
        date=parsed_date,
        description=str(data.get("description") or "").strip(),
        deposit=deposit,
    )


# ─── Link-existing extraction ────────────────────────────────────────────────


@dataclass(frozen=True)
class ParsedLink:
    """A «привяжи расход X к регулярному/желаемому Y» request. Descriptors are
    matched against actual transactions / wishlist items in Python."""

    expense_name: str
    expense_date: Date | None
    target_name: str
    target_category: str
    target_kind: str  # "regular" | "wishlist" | ""


_LINK_SCHEMA: dict = {
    "name": "link_request",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "expense_name": {"type": "string"},
            "expense_date": {"type": "string"},
            "target_name": {"type": "string"},
            "target_category": {"type": "string"},
            "target_kind": {"type": "string", "enum": ["regular", "wishlist", ""]},
        },
        "required": [
            "expense_name",
            "expense_date",
            "target_name",
            "target_category",
            "target_kind",
        ],
        "additionalProperties": False,
    },
}


async def parse_link(
    client: AsyncOpenAI,
    *,
    model: str,
    text: str,
    today: Date,
) -> ParsedLink:
    """Extract the link request descriptors. `expense_date` is parsed to a date
    (current year assumed when the year is omitted), or None when absent."""
    sys = (
        "Ты разбираешь просьбу ПРИВЯЗАТЬ существующую запись расхода к "
        "регулярному расходу или желаемой покупке. Верни ТОЛЬКО JSON.\n"
        f"Сегодня: {today.isoformat()}.\n"
        "- expense_name: название/назначение привязываемого расхода (например "
        '"Откачка").\n'
        "- expense_date: дата этого расхода в формате YYYY-MM-DD. Если указан "
        'день без года (например «27.05») — подставь текущий год. Если даты '
        'нет — пустая строка.\n'
        "- target_name: название регулярного расхода / желаемой покупки, к "
        'которому привязываем (например "Откачка").\n'
        "- target_category: категория цели, если указана (например "
        '"Жилье/ЖКХ"), иначе пустая строка.\n'
        '- target_kind: "regular" если это регулярный расход, "wishlist" если '
        'желаемая покупка, иначе пустая строка.'
    )
    messages: list[dict] = [
        {"role": "system", "content": sys},
        {
            "role": "user",
            "content": "Привяжи расход Откачка от 27.05 к регулярному расходу Откачка из категории Жилье/ЖКХ",
        },
        {
            "role": "assistant",
            "content": json.dumps(
                {
                    "expense_name": "Откачка",
                    "expense_date": f"{today.year}-05-27",
                    "target_name": "Откачка",
                    "target_category": "Жилье/ЖКХ",
                    "target_kind": "regular",
                },
                ensure_ascii=False,
            ),
        },
        {"role": "user", "content": text},
    ]
    try:
        resp = await client.chat.completions.create(
            model=model,
            messages=messages,  # type: ignore[arg-type]
            response_format={"type": "json_schema", "json_schema": _LINK_SCHEMA},  # type: ignore[arg-type]
            temperature=0.0,
            max_tokens=200,
        )
    except OpenAIAPIError as e:
        raise LLMParseError("LLM недоступен, попробуйте ещё раз через минуту.") from e

    content = (resp.choices[0].message.content or "").strip()
    try:
        data = json.loads(content)
    except json.JSONDecodeError as e:
        raise LLMParseError("Не удалось разобрать ответ LLM.") from e
    if isinstance(data, list) and len(data) == 1 and isinstance(data[0], dict):
        data = data[0]
    if not isinstance(data, dict):
        raise LLMParseError("Не удалось разобрать ответ LLM.")

    expense_name = str(data.get("expense_name") or "").strip()
    target_name = str(data.get("target_name") or "").strip()
    if not expense_name or not target_name:
        raise LLMParseError("Не понял, какой расход к чему привязать.")

    expense_date: Date | None = None
    raw_date = str(data.get("expense_date") or "").strip()
    if raw_date:
        try:
            expense_date = datetime.strptime(raw_date, "%Y-%m-%d").date()
        except ValueError:
            expense_date = None

    kind = str(data.get("target_kind") or "").strip()
    if kind not in ("regular", "wishlist", ""):
        kind = ""
    return ParsedLink(
        expense_name=expense_name,
        expense_date=expense_date,
        target_name=target_name,
        target_category=str(data.get("target_category") or "").strip(),
        target_kind=kind,
    )


# ─── Detail-request creation extraction ──────────────────────────────────────


@dataclass(frozen=True)
class ParsedDetailRequest:
    """A «создай ЗнД на <кого> на <сумму> категория <X>» request. `assignee` is
    a display-name fragment resolved against the family list in Python."""

    amount: float
    category: str
    assignee: str
    purpose: str
    deposit: str


_DR_SCHEMA: dict = {
    "name": "detail_request_create",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "amount": {"type": "number"},
            "category": {"type": "string"},
            "assignee": {"type": "string"},
            "purpose": {"type": "string"},
            "deposit": {"type": "string", "enum": ["bank", "cash"]},
        },
        "required": ["amount", "category", "assignee", "purpose", "deposit"],
        "additionalProperties": False,
    },
}


async def parse_detail_request(
    client: AsyncOpenAI,
    *,
    model: str,
    text: str,
    expense_categories: list[str],
) -> ParsedDetailRequest:
    """Extract the lump-sum expense + assignee for a new detail-request.
    `category` ∈ expense categories (fallback «Прочее»)."""
    cats_line = ", ".join(expense_categories) or "—"
    sys = (
        "Ты разбираешь просьбу СОЗДАТЬ запрос на детализацию (ЗнД). По ней "
        "создаётся расход на сумму и категорию, и назначается исполнитель. "
        "Верни ТОЛЬКО JSON.\n"
        "- amount: сумма расхода в рублях, положительное число.\n"
        f"- category: ровно одно из категорий расходов: {cats_line}. Если не "
        'подходит — "Прочее".\n'
        "- assignee: имя члена семьи, на которого назначается ЗнД (как в "
        'тексте, например "Ира").\n'
        "- purpose: назначение/описание траты, если указано, иначе пустая "
        "строка.\n"
        '- deposit: "cash" если наличными, иначе "bank".'
    )
    messages: list[dict] = [
        {"role": "system", "content": sys},
        {"role": "user", "content": "создай ЗнД на Иру на 5000 категория Продукты"},
        {
            "role": "assistant",
            "content": json.dumps(
                {
                    "amount": 5000,
                    "category": "Продукты",
                    "assignee": "Ира",
                    "purpose": "",
                    "deposit": "bank",
                },
                ensure_ascii=False,
            ),
        },
        {"role": "user", "content": text},
    ]
    try:
        resp = await client.chat.completions.create(
            model=model,
            messages=messages,  # type: ignore[arg-type]
            response_format={"type": "json_schema", "json_schema": _DR_SCHEMA},  # type: ignore[arg-type]
            temperature=0.0,
            max_tokens=200,
        )
    except OpenAIAPIError as e:
        raise LLMParseError("LLM недоступен, попробуйте ещё раз через минуту.") from e

    content = (resp.choices[0].message.content or "").strip()
    try:
        data = json.loads(content)
    except json.JSONDecodeError as e:
        raise LLMParseError("Не удалось разобрать ответ LLM.") from e
    if isinstance(data, list) and len(data) == 1 and isinstance(data[0], dict):
        data = data[0]
    if not isinstance(data, dict):
        raise LLMParseError("Не удалось разобрать ответ LLM.")

    try:
        amount = float(data.get("amount", 0))
    except (TypeError, ValueError) as e:
        raise LLMParseError("Не понял сумму.") from e
    if amount <= 0:
        raise LLMParseError("Сумма должна быть положительной.")
    assignee = str(data.get("assignee") or "").strip()
    if not assignee:
        raise LLMParseError("Не понял, на кого назначить ЗнД.")
    category = _resolve_category(str(data.get("category") or "").strip(), expense_categories)
    deposit = str(data.get("deposit") or "bank").strip().lower()
    if deposit not in ("bank", "cash"):
        deposit = "bank"
    return ParsedDetailRequest(
        amount=amount,
        category=category,
        assignee=assignee,
        purpose=str(data.get("purpose") or "").strip(),
        deposit=deposit,
    )


def make_llm_client(base_url: str, api_key: str) -> AsyncOpenAI:
    """Construct the OpenAI client pointing at the configured endpoint.

    `api_key` is required by the SDK even when the server (e.g. llama.cpp)
    ignores it — we default to a sentinel "not-needed" string. httpx
    underneath honours NO_PROXY so the intra-stack route to host.docker.internal
    stays direct.
    """
    return AsyncOpenAI(base_url=base_url, api_key=api_key or "not-needed")
