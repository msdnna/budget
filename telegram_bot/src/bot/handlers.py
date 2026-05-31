from __future__ import annotations

import logging
import os
import re
import tempfile
from datetime import date as Date
from html import escape

from aiogram import F, Router
from aiogram.filters import Command, CommandObject, CommandStart, StateFilter
from aiogram.fsm.context import FSMContext
from aiogram.types import (
    CallbackQuery,
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    Message,
)
from openai import AsyncOpenAI

from .api_client import APIError, BudgetAPI, LinkedUser, RegularItem, TelegramContext
from .draft import (
    CB_BACK_TO_CONFIRM,
    CB_CANCEL,
    CB_CONFIRM,
    CB_DR_CREATE_CANCEL,
    CB_DR_CREATE_CONFIRM,
    CB_DR_PICK,
    CB_EDIT_AMOUNT,
    CB_EDIT_CATEGORY,
    CB_LINK_CANCEL,
    CB_LINK_CONFIRM,
    CB_PICK_CATEGORY,
    CB_WL_CANCEL,
    CB_WL_CONFIRM,
    DraftStates,
    draft_from_dict,
    draft_to_dict,
    wishlist_from_dict,
    wishlist_to_dict,
)
from .intents import (
    INTENT_DETAIL_REQUEST,
    INTENT_LINK,
    INTENT_RECURRING,
    INTENT_WISHLIST,
    classify_intent,
)
from .llm_client import (
    LLMParseError,
    ParsedLink,
    ParsedTransaction,
    ParsedWishlist,
    parse_detail_request,
    parse_link,
    parse_recurring,
    parse_transaction,
    parse_wishlist,
)
from .whisper_client import WhisperTranscriber

logger = logging.getLogger(__name__)

router = Router()

# Crockford-style alphabet — same set used by the backend
# (handlers/telegram.go: linkCodeAlphabet). Six chars, all uppercase.
_LINK_CODE_RE = re.compile(r"^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$")


# ─── Static text ──────────────────────────────────────────────────────────


def _format_help(linked: LinkedUser | None) -> str:
    if linked is None:
        return (
            "👋 Привет! Я бот для учёта семейного бюджета.\n\n"
            "Чтобы начать пользоваться, привяжите бот к своей учётке:\n"
            "1. Откройте веб- или Android-приложение бюджета.\n"
            "2. Settings → Telegram → «Привязать».\n"
            "3. Пришлите мне команду <code>/link КОД</code> с показанным кодом.\n\n"
            "После привязки можно будет писать в свободной форме, например:\n"
            "<i>«продукты магнит 2300»</i> или <i>«пришла зарплата 50000»</i>."
        )
    return (
        f"✅ Привязано: <b>{escape(linked.display_name)}</b>\n\n"
        "Пишите голосом или текстом в свободной форме — я разберу смысл и предложу "
        "вариант, который нужно подтвердить кнопкой.\n\n"
        "<b>Что я понимаю:</b>\n"
        "💸 <b>Расход</b> — <i>продукты магнит 2300 наличными</i>\n"
        "💰 <b>Доход</b> — <i>пришла зарплата за май 50000</i>\n"
        "🛒 <b>Желаемая покупка</b> — <i>хочу купить робот-пылесос Xiaomi в DNS за 25000</i>\n"
        "🔁 <b>Регулярный платёж</b> — <i>оплатил счёт за воду, май, 10 кубов, 700</i>\n"
        "🔗 <b>Привязать расход</b> — <i>привяжи расход Откачка от 27.05 к регулярному "
        "расходу Откачка</i>\n\n"
        "<b>Команды:</b>\n"
        "/dr — заполнить запрос на детализацию (ЗнД)\n"
        "/dr_close — закрыть текущий ЗнД\n"
        "/cancel — отменить текущую операцию\n"
        "/help — эта подсказка\n"
        "/unlink — как отвязать аккаунт"
    )


def _format_parsed(p: ParsedTransaction) -> str:
    """Render the parsed draft for human review. HTML-escape user input —
    Telegram's HTML parse-mode would otherwise mangle `<`, `&`, etc."""
    if p.wishlist_id:
        title = "🔁 Регулярный платёж"
    elif p.type == "income":
        title = "💰 Доход"
    else:
        title = "💸 Расход"
    cp = escape(p.counterparty) if p.counterparty else "—"
    deposit_label = "💵 Наличные" if p.deposit == "cash" else "💳 Банковская карта"
    lines = [
        f"<b>{title}</b>",
        f"Сумма: <b>{p.amount:g} ₽</b>",
        f"Категория: <b>{escape(p.category)}</b>",
        f"Источник/назначение: {cp}",
        f"Счёт: {deposit_label}",
        f"Дата: {p.date.isoformat()}",
    ]
    if p.wishlist_id and p.wishlist_name:
        lines.append(f"Привязка: <b>{escape(p.wishlist_name)}</b>")
    if p.description:
        lines.append(f"Детали: <i>{escape(p.description)}</i>")
    return "\n".join(lines)


def _format_wishlist(p: ParsedWishlist) -> str:
    lines = [
        "🛒 <b>Желаемая покупка</b>",
        f"Название: <b>{escape(p.name)}</b>",
        f"Цена: <b>{p.estimated_cost:g} ₽</b>",
        f"Категория: <b>{escape(p.category)}</b>",
    ]
    if p.notes:
        lines.append(f"Заметки: <i>{escape(p.notes)}</i>")
    return "\n".join(lines)


def _wishlist_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(text="✅ Добавить", callback_data=CB_WL_CONFIRM),
                InlineKeyboardButton(text="❌ Отмена", callback_data=CB_WL_CANCEL),
            ]
        ]
    )


def _confirm_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(text="✅ Сохранить", callback_data=CB_CONFIRM),
                InlineKeyboardButton(text="❌ Отмена", callback_data=CB_CANCEL),
            ],
            [
                InlineKeyboardButton(text="✏️ Сумма", callback_data=CB_EDIT_AMOUNT),
                InlineKeyboardButton(text="✏️ Категория", callback_data=CB_EDIT_CATEGORY),
            ],
        ]
    )


def _category_keyboard(categories: list[str]) -> InlineKeyboardMarkup:
    """Two-column grid of category picks + a "Back" row.

    Callback data is `tx:pc:<name>`; Telegram caps total at 64 bytes, and
    category names rarely exceed ~20 chars in practice. If they do, the
    button silently fails to register — acceptable for an MVP, fix later
    with an index-based lookup.
    """
    rows: list[list[InlineKeyboardButton]] = []
    cur: list[InlineKeyboardButton] = []
    for name in categories:
        cur.append(InlineKeyboardButton(text=name, callback_data=f"{CB_PICK_CATEGORY}{name}"))
        if len(cur) == 2:
            rows.append(cur)
            cur = []
    if cur:
        rows.append(cur)
    rows.append([InlineKeyboardButton(text="◀️ Назад", callback_data=CB_BACK_TO_CONFIRM)])
    return InlineKeyboardMarkup(inline_keyboard=rows)


# ─── Linking commands ─────────────────────────────────────────────────────


@router.message(CommandStart())
async def cmd_start(message: Message, api: BudgetAPI, state: FSMContext) -> None:
    """Greeting + reminder of how to link, branching on the user's link state."""
    if message.from_user is None:
        return
    # /start cancels any half-finished draft — fresh slate per session boot.
    await state.clear()
    try:
        linked = await api.lookup_user(message.from_user.id)
    except APIError:
        logger.exception("lookup_user failed")
        await message.answer("Не удалось связаться с бэкендом, попробуйте позже.")
        return
    await message.answer(_format_help(linked))


@router.message(Command("help"))
async def cmd_help(message: Message, api: BudgetAPI) -> None:
    """Same content as /start but without clearing an in-flight draft — the
    user may just want a reminder of capabilities mid-session."""
    if message.from_user is None:
        return
    try:
        linked = await api.lookup_user(message.from_user.id)
    except APIError:
        logger.exception("lookup_user failed")
        await message.answer("Не удалось связаться с бэкендом, попробуйте позже.")
        return
    await message.answer(_format_help(linked))


@router.message(Command("link"))
async def cmd_link(message: Message, command: CommandObject, api: BudgetAPI) -> None:
    """`/link CODE` — finalize the binding initiated in the budget app."""
    if message.from_user is None:
        return
    raw = (command.args or "").strip().upper()
    if not _LINK_CODE_RE.fullmatch(raw):
        await message.answer(
            "Формат: <code>/link КОД</code>\n"
            "Код — 6 латинских заглавных или цифр (без I/L/O/0/1), посмотрите его в "
            "Settings → Telegram → «Привязать» в приложении бюджета."
        )
        return
    try:
        await api.confirm_link(
            code=raw,
            telegram_user_id=message.from_user.id,
            telegram_username=message.from_user.username,
        )
    except APIError as e:
        if e.status == 400:
            await message.answer("❌ Код недействителен или истёк. Сгенерируйте новый в приложении.")
            return
        logger.exception("confirm_link failed")
        await message.answer("Ошибка бэкенда, попробуйте позже.")
        return
    await message.answer("✅ Привязка выполнена. Пришлите /start чтобы увидеть подсказку.")


@router.message(Command("unlink"))
async def cmd_unlink(message: Message) -> None:
    await message.answer(
        "Чтобы отвязать аккаунт, откройте приложение бюджета: "
        "Settings → Telegram → «Отвязать»."
    )


@router.message(Command("cancel"))
async def cmd_cancel(message: Message, state: FSMContext) -> None:
    """Emergency-exit any FSM state — useful if confirm-keyboard got eaten."""
    current = await state.get_state()
    if current is None:
        await message.answer("Ничего не отменяю.")
        return
    await state.clear()
    await message.answer("Отменено.")


# ─── Free-text parsing entry point ────────────────────────────────────────


async def _enter_confirm(message: Message, state: FSMContext, parsed: ParsedTransaction) -> None:
    await state.set_state(DraftStates.awaiting_confirm)
    await state.set_data({"draft": draft_to_dict(parsed)})
    await message.answer(_format_parsed(parsed), reply_markup=_confirm_keyboard())


@router.message(StateFilter(DraftStates.awaiting_amount), F.text)
async def on_amount_reply(message: Message, state: FSMContext) -> None:
    """Capture the user-typed amount after they tapped ✏️ Сумма."""
    if not message.text:
        return
    raw = message.text.strip().replace(",", ".").replace(" ", "")
    try:
        amount = float(raw)
    except ValueError:
        await message.answer("Не понял сумму. Пришлите число, например 1234.50")
        return
    if amount <= 0:
        await message.answer("Сумма должна быть положительной.")
        return
    data = await state.get_data()
    draft = draft_from_dict(data["draft"])
    new = ParsedTransaction(
        type=draft.type,
        amount=amount,
        category=draft.category,
        counterparty=draft.counterparty,
        date=draft.date,
        description=draft.description,
        deposit=draft.deposit,
    )
    await _enter_confirm(message, state, new)


async def _resolve_linked_or_complain(message: Message, api: BudgetAPI) -> LinkedUser | None:
    """Common preamble: look up the linked user, surface friendly errors.
    Returns None if the caller should bail out (already answered to user)."""
    if message.from_user is None:
        return None
    try:
        linked = await api.lookup_user(message.from_user.id)
    except APIError:
        logger.exception("lookup_user failed")
        await message.answer("Не могу достучаться до бэкенда.")
        return None
    if linked is None:
        await message.answer("Сначала привяжите аккаунт. Введите /start для инструкции.")
        return None
    return linked


async def _parse_and_confirm(
    message: Message,
    state: FSMContext,
    api: BudgetAPI,
    llm: AsyncOpenAI,
    llm_model: str,
    linked: LinkedUser,
    text: str,
) -> None:
    """Entry point for both text-in and voice-in flows: load context, classify
    intent, dispatch to the right handler. Voice delegates here after Whisper
    transcription so routing logic stays single source of truth."""
    try:
        ctx = await api.get_context(linked.user_id)
    except APIError:
        logger.exception("get_context failed")
        await message.answer("Не могу загрузить контекст, попробуйте позже.")
        return

    await message.bot.send_chat_action(message.chat.id, "typing")

    intent = await classify_intent(
        llm, model=llm_model, text=text, triggers=ctx.intent_triggers
    )
    logger.info("classified intent=%s for %r", intent, text)

    if intent == INTENT_WISHLIST:
        await _handle_wishlist(message, state, api, llm, llm_model, linked, ctx, text)
    elif intent == INTENT_RECURRING:
        await _handle_recurring(message, state, api, llm, llm_model, linked, ctx, text)
    elif intent == INTENT_LINK:
        await _handle_link(message, state, api, llm, llm_model, linked, ctx, text)
    elif intent == INTENT_DETAIL_REQUEST:
        await _handle_detail_request_create(
            message, state, api, llm, llm_model, linked, ctx, text
        )
    else:  # INTENT_TRANSACTION (and the safe fallback)
        await _handle_transaction(message, state, api, llm, llm_model, linked, ctx, text)


async def _handle_transaction(
    message: Message,
    state: FSMContext,
    api: BudgetAPI,
    llm: AsyncOpenAI,
    llm_model: str,
    linked: LinkedUser,
    ctx: TelegramContext,
    text: str,
) -> None:
    """Plain income/expense: parse → draft → confirm keyboard."""
    try:
        parsed = await parse_transaction(
            llm,
            model=llm_model,
            text=text,
            today=Date.today(),
            expense=ctx.expense,
            income=ctx.income,
            glossary=ctx.glossary,
            counterparties=ctx.counterparties,
        )
    except LLMParseError as e:
        await message.answer(
            f"⚠️ {escape(e.reason)}\n\n"
            "Попробуйте переформулировать — например: "
            "<i>«продукты магнит 2300»</i> или <i>«пришла зарплата 50000»</i>."
        )
        return

    # Income template: when the user has a uniform history for this income
    # category, clone the field layout and change only amount + date.
    if parsed.type == "income":
        templated = await _maybe_apply_income_template(api, linked, parsed, text)
        if templated is not None:
            await message.answer("📋 Заполнено по образцу прошлых записей — проверьте и сохраните.")
            await _enter_confirm(message, state, templated)
            return

    await _enter_confirm(message, state, parsed)


def _has_avans(s: str) -> bool:
    """True if the text carries an «аванс» marker (the одно of the few income
    sub-variants we template separately)."""
    return "аванс" in (s or "").lower()


async def _maybe_apply_income_template(
    api: BudgetAPI,
    linked: LinkedUser,
    parsed: ParsedTransaction,
    text: str,
) -> ParsedTransaction | None:
    """Clone the field layout of prior uniform income records for this category,
    keeping only the user's amount + date. Returns None when there isn't enough
    uniform history (≤2 matching records) — caller then uses the LLM parse.

    Variant handling: «аванс» messages template against past «аванс» records;
    plain salary templates against records WITHOUT an «аванс» marker — so the
    доп-поле «Аванс» is preserved or omitted exactly as in history.
    """
    want_avans = _has_avans(parsed.description) or _has_avans(text)
    try:
        history = await api.list_transactions(
            linked.user_id, tx_type="income", category=parsed.category, limit=50
        )
    except APIError:
        logger.exception("list_transactions (income template) failed")
        return None

    matching = [tx for tx in history if _has_avans(tx.get("description", "")) == want_avans]
    # «больше двух» ⇒ need at least 3 prior uniform records to trust the layout.
    if len(matching) <= 2:
        return None

    # Newest first — ISO date strings sort lexicographically.
    matching.sort(key=lambda tx: tx.get("date", ""), reverse=True)
    template = matching[0]

    return ParsedTransaction(
        type="income",
        amount=parsed.amount,
        category=parsed.category,
        counterparty=str(template.get("source") or parsed.counterparty),
        date=parsed.date,
        description=str(template.get("description") or ""),
        deposit=str(template.get("deposit") or "bank"),
    )


# ─── Intent handlers (filled in incrementally; see phases 7/8) ──────────────


async def _handle_wishlist(
    message: Message,
    state: FSMContext,
    api: BudgetAPI,
    llm: AsyncOpenAI,
    llm_model: str,
    linked: LinkedUser,
    ctx: TelegramContext,
    text: str,
) -> None:
    """Planned purchase → wishlist item (frequency=once), with confirm."""
    try:
        wl_cats = await api.list_wishlist_categories(linked.user_id)
    except APIError:
        logger.exception("list_wishlist_categories failed")
        await message.answer("Не могу загрузить категории желаний, попробуйте позже.")
        return
    try:
        parsed = await parse_wishlist(llm, model=llm_model, text=text, wishlist_categories=wl_cats)
    except LLMParseError as e:
        await message.answer(
            f"⚠️ {escape(e.reason)}\n\n"
            "Например: <i>«хочу купить робот-пылесос Xiaomi в DNS за 25000»</i>."
        )
        return
    await state.set_state(DraftStates.awaiting_wishlist_confirm)
    await state.set_data({"wishlist": wishlist_to_dict(parsed)})
    await message.answer(_format_wishlist(parsed), reply_markup=_wishlist_keyboard())


async def _handle_recurring(
    message: Message,
    state: FSMContext,
    api: BudgetAPI,
    llm: AsyncOpenAI,
    llm_model: str,
    linked: LinkedUser,
    ctx: TelegramContext,
    text: str,
) -> None:
    """Recurring-payment → expense linked to the matched regular item. Falls
    back to the plain-transaction flow when no regular item matches."""
    try:
        regulars = await api.get_regular_items(linked.user_id)
    except APIError:
        logger.exception("get_regular_items failed")
        await message.answer("Не могу загрузить регулярные расходы, попробуйте позже.")
        return

    if not regulars:
        # No regular items at all — treat as an ordinary expense.
        await _handle_transaction(message, state, api, llm, llm_model, linked, ctx, text)
        return

    try:
        parsed = await parse_recurring(
            llm,
            model=llm_model,
            text=text,
            today=Date.today(),
            regular_items=[(r.name, r.category) for r in regulars],
        )
    except LLMParseError as e:
        await message.answer(f"⚠️ {escape(e.reason)}")
        return

    match = _match_regular(parsed.item_name, regulars)
    if match is None:
        # Couldn't tie it to a regular item — fall back to a normal expense so
        # the money is still recorded; the user can link it later.
        await message.answer(
            "Не нашёл подходящий регулярный расход — оформляю как обычную трату."
        )
        await _handle_transaction(message, state, api, llm, llm_model, linked, ctx, text)
        return

    # Inherit category/deposit/purpose from the regular item; numeric data
    # (amount, date) and the measurable detail (description) come from the user.
    tx = ParsedTransaction(
        type="expense",
        amount=parsed.amount,
        category=match.category,
        counterparty=match.name,
        date=parsed.date,
        description=parsed.description,
        deposit=match.deposit or "bank",
        wishlist_id=match.id,
        wishlist_name=match.name,
    )
    await _enter_confirm(message, state, tx)


def _match_regular(item_name: str, regulars: list[RegularItem]) -> RegularItem | None:
    """Resolve the model-picked `item_name` to a RegularItem. Exact (case-
    insensitive) match first, then a contains-match either direction to absorb
    minor phrasing differences. Returns None when nothing fits."""
    if not item_name:
        return None
    needle = item_name.strip().lower()
    for r in regulars:
        if r.name.strip().lower() == needle:
            return r
    for r in regulars:
        rn = r.name.strip().lower()
        if rn and (rn in needle or needle in rn):
            return r
    return None


def _tx_label(tx: dict) -> str:
    """Compact one-line label for an expense candidate."""
    name = tx.get("purpose") or tx.get("description") or tx.get("category") or "—"
    date = str(tx.get("date") or "")[:10]
    return f"«{name}» {float(tx.get('amount', 0)):g}₽ от {date}"


def _match_expense_candidates(expenses: list[dict], parsed_link: ParsedLink) -> list[dict]:
    """Filter unlinked expenses by name (purpose/description/category contains
    the descriptor) and, when given, by exact date."""
    needle = parsed_link.expense_name.strip().lower()
    want_date = parsed_link.expense_date.isoformat() if parsed_link.expense_date else None
    out: list[dict] = []
    for tx in expenses:
        haystack = " ".join(
            str(tx.get(k, "")) for k in ("purpose", "description", "category", "source")
        ).lower()
        if needle and needle not in haystack:
            continue
        if want_date and str(tx.get("date") or "")[:10] != want_date:
            continue
        out.append(tx)
    return out


def _match_target_item(items: list[dict], parsed_link: ParsedLink) -> dict | None:
    """Resolve the wishlist/regular target by name (+ optional category +
    kind). Exact name match wins, then contains-match."""
    needle = parsed_link.target_name.strip().lower()
    cat = parsed_link.target_category.strip().lower()
    kind = parsed_link.target_kind

    def kind_ok(it: dict) -> bool:
        freq = str(it.get("frequency") or "once")
        if kind == "regular":
            return freq != "once"
        if kind == "wishlist":
            return freq == "once"
        return True

    def cat_ok(it: dict) -> bool:
        return not cat or cat in str(it.get("category") or "").lower()

    pool = [it for it in items if kind_ok(it) and cat_ok(it)]
    for it in pool:
        if str(it.get("name") or "").strip().lower() == needle:
            return it
    for it in pool:
        nm = str(it.get("name") or "").strip().lower()
        if nm and (needle in nm or nm in needle):
            return it
    return None


async def _handle_link(
    message: Message,
    state: FSMContext,
    api: BudgetAPI,
    llm: AsyncOpenAI,
    llm_model: str,
    linked: LinkedUser,
    ctx: TelegramContext,
    text: str,
) -> None:
    """Link an existing unlinked expense to a regular/wishlist item, with a
    confirm step naming both sides of the link."""
    try:
        parsed = await parse_link(llm, model=llm_model, text=text, today=Date.today())
    except LLMParseError as e:
        await message.answer(f"⚠️ {escape(e.reason)}")
        return

    try:
        expenses = await api.list_transactions(linked.user_id, unlinked=True, limit=100)
        items = await api.list_wishlist(linked.user_id)
    except APIError:
        logger.exception("link lookup failed")
        await message.answer("Не могу загрузить данные, попробуйте позже.")
        return

    candidates = _match_expense_candidates(expenses, parsed)
    if not candidates:
        await message.answer(
            f"Не нашёл непривязанный расход «{escape(parsed.expense_name)}»"
            + (f" от {parsed.expense_date.isoformat()}" if parsed.expense_date else "")
            + ". Уточните название или дату."
        )
        return
    if len(candidates) > 1:
        preview = "\n".join(f"• {escape(_tx_label(t))}" for t in candidates[:5])
        await message.answer(
            "Нашёл несколько подходящих расходов — уточните дату:\n" + preview
        )
        return

    target = _match_target_item(items, parsed)
    if target is None:
        await message.answer(
            f"Не нашёл цель «{escape(parsed.target_name)}»"
            + (f" в категории {escape(parsed.target_category)}" if parsed.target_category else "")
            + ". Проверьте название."
        )
        return

    tx = candidates[0]
    await state.set_state(DraftStates.awaiting_link_confirm)
    await state.set_data({"tx_id": tx["id"], "wl_id": target["id"]})
    freq = str(target.get("frequency") or "once")
    kind_label = "регулярному расходу" if freq != "once" else "желаемой покупке"
    await message.answer(
        f"🔗 Привязать расход {escape(_tx_label(tx))}\n"
        f"к {kind_label} <b>{escape(str(target.get('name') or ''))}</b> "
        f"({escape(str(target.get('category') or ''))})?",
        reply_markup=InlineKeyboardMarkup(
            inline_keyboard=[
                [
                    InlineKeyboardButton(text="✅ Привязать", callback_data=CB_LINK_CONFIRM),
                    InlineKeyboardButton(text="❌ Отмена", callback_data=CB_LINK_CANCEL),
                ]
            ]
        ),
    )


def _family_label(u: dict) -> str:
    return str(u.get("display_name") or "—")


def _match_family(name: str, family: list[dict]) -> list[dict]:
    """Resolve an assignee display-name fragment to family members. Exact match
    short-circuits to a single result; otherwise contains-matches (so «Ира»
    finds «Ирина»)."""
    needle = name.strip().lower()
    if not needle:
        return []
    exact = [u for u in family if str(u.get("display_name") or "").strip().lower() == needle]
    if exact:
        return exact
    return [u for u in family if needle in str(u.get("display_name") or "").lower()]


async def _handle_detail_request_create(
    message: Message,
    state: FSMContext,
    api: BudgetAPI,
    llm: AsyncOpenAI,
    llm_model: str,
    linked: LinkedUser,
    ctx: TelegramContext,
    text: str,
) -> None:
    """«Создай ЗнД на <кого> на <сумму> категория <X>» → parse, resolve the
    assignee, confirm. On confirm a lump-sum expense is created and a
    detail-request opened over it (see on_dr_create_confirm)."""
    try:
        parsed = await parse_detail_request(
            llm, model=llm_model, text=text, expense_categories=ctx.expense_names()
        )
    except LLMParseError as e:
        await message.answer(
            f"⚠️ {escape(e.reason)}\n\nНапример: <i>«создай ЗнД на Иру на 5000 категория Продукты»</i>."
        )
        return

    try:
        family = await api.list_family(linked.user_id)
    except APIError:
        logger.exception("list_family failed")
        await message.answer("Не могу загрузить список семьи, попробуйте позже.")
        return

    matches = _match_family(parsed.assignee, family)
    if not matches:
        await message.answer(
            f"Не нашёл в семье пользователя «{escape(parsed.assignee)}». "
            "Проверьте имя (как в профиле)."
        )
        return
    if len(matches) > 1:
        names = ", ".join(escape(_family_label(u)) for u in matches[:6])
        await message.answer(f"Несколько подходящих: {names}. Уточните имя.")
        return

    assignee = matches[0]
    await state.set_state(DraftStates.awaiting_dr_create_confirm)
    await state.set_data(
        {
            "amount": parsed.amount,
            "category": parsed.category,
            "purpose": parsed.purpose,
            "deposit": parsed.deposit,
            "assignee_id": assignee["user_id"],
            "assignee_name": _family_label(assignee),
        }
    )
    purpose_line = f"\nНазначение: {escape(parsed.purpose)}" if parsed.purpose else ""
    await message.answer(
        "📝 <b>Создать ЗнД</b>\n"
        f"Сумма: <b>{parsed.amount:g} ₽</b>\n"
        f"Категория: <b>{escape(parsed.category)}</b>\n"
        f"Исполнитель: <b>{escape(_family_label(assignee))}</b>"
        f"{purpose_line}",
        reply_markup=InlineKeyboardMarkup(
            inline_keyboard=[
                [
                    InlineKeyboardButton(text="✅ Создать", callback_data=CB_DR_CREATE_CONFIRM),
                    InlineKeyboardButton(text="❌ Отмена", callback_data=CB_DR_CREATE_CANCEL),
                ]
            ]
        ),
    )


async def _transcribe_voice(message: Message, whisper: WhisperTranscriber) -> str | None:
    """Download the voice/audio attachment and transcribe it. Answers the user
    and returns None on any failure or empty result; otherwise returns the
    transcript. Shared by the free-form voice flow and the ЗнД-fill flow."""
    file_id: str | None = None
    if message.voice is not None:
        file_id = message.voice.file_id
    elif message.audio is not None:
        file_id = message.audio.file_id
    if file_id is None:
        return None

    await message.bot.send_chat_action(message.chat.id, "typing")

    # tempfile lives only for the duration of transcribe; `.ogg` suffix is
    # informational — faster-whisper detects format from container.
    with tempfile.NamedTemporaryFile(suffix=".ogg", delete=False) as tmp:
        tmp_path = tmp.name
    try:
        tg_file = await message.bot.get_file(file_id)
        if tg_file.file_path is None:
            await message.answer("Не удалось получить голосовое сообщение.")
            return None
        await message.bot.download_file(tg_file.file_path, destination=tmp_path)
        try:
            text = await whisper.transcribe(tmp_path)
        except Exception:
            logger.exception("whisper.transcribe failed")
            await message.answer("Не удалось распознать аудио. Попробуйте написать текстом.")
            return None
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass

    if not text:
        await message.answer(
            "🎤 Не услышал текст — возможно тишина или слишком шумно. Попробуйте ещё раз."
        )
        return None
    # Echo what we heard so the user can spot misrecognitions early.
    await message.answer(f"🎤 <i>{escape(text)}</i>")
    return text


# ─── ЗнД (detail-request) fill flow ─────────────────────────────────────────
#
# These state-scoped handlers MUST be registered before the catch-all
# fallback_text / fallback_voice below — aiogram dispatches in registration
# order, so an earlier match wins.


@router.message(Command("dr"))
async def cmd_dr(message: Message, state: FSMContext, api: BudgetAPI) -> None:
    """Start filling an open detail-request assigned to the caller."""
    linked = await _resolve_linked_or_complain(message, api)
    if linked is None:
        return
    try:
        drs = await api.list_detail_requests(linked.user_id, assignee_id="me", status="open")
    except APIError:
        logger.exception("list_detail_requests failed")
        await message.answer("Не могу загрузить ЗнД, попробуйте позже.")
        return
    if not drs:
        await message.answer("Нет открытых ЗнД, назначенных на вас.")
        return
    if len(drs) == 1:
        await _enter_dr_fill(message, state, drs[0])
        return
    rows = [
        [
            InlineKeyboardButton(
                text=f"{float(dr.get('target_amount', 0)):g}₽ от "
                f"{(dr.get('creator') or {}).get('display_name', '—')}",
                callback_data=f"{CB_DR_PICK}{dr['id']}",
            )
        ]
        for dr in drs[:8]
    ]
    await message.answer(
        "Выберите ЗнД для заполнения:", reply_markup=InlineKeyboardMarkup(inline_keyboard=rows)
    )


async def _enter_dr_fill(message: Message, state: FSMContext, dr: dict) -> None:
    await state.set_state(DraftStates.filling_detail_request)
    await state.set_data({"dr_id": dr["id"], "count": 0})
    target = float(dr.get("target_amount", 0))
    await message.answer(
        f"📝 Заполняем ЗнД на <b>{target:g} ₽</b>.\n"
        "Накидывайте расходы по одному (текстом или голосом), например "
        "<i>«продукты 850»</i>.\n"
        "Когда закончите — /dr_close. Прервать — /cancel."
    )


@router.callback_query(StateFilter(None), F.data.startswith(CB_DR_PICK))
async def on_dr_pick(cb: CallbackQuery, state: FSMContext, api: BudgetAPI) -> None:
    if cb.from_user is None or cb.message is None or cb.data is None:
        await cb.answer()
        return
    dr_id = cb.data[len(CB_DR_PICK) :]
    linked = await api.lookup_user(cb.from_user.id)
    if linked is None:
        await cb.answer("Аккаунт не привязан", show_alert=True)
        return
    try:
        drs = await api.list_detail_requests(linked.user_id, assignee_id="me", status="open")
    except APIError:
        await cb.answer("Бэкенд недоступен", show_alert=True)
        return
    dr = next((d for d in drs if d.get("id") == dr_id), None)
    if dr is None:
        await cb.answer("ЗнД не найден или уже закрыт", show_alert=True)
        return
    await _enter_dr_fill(cb.message, state, dr)
    await cb.answer()


@router.message(Command("dr_close"), StateFilter(DraftStates.filling_detail_request))
async def cmd_dr_close(message: Message, state: FSMContext, api: BudgetAPI) -> None:
    linked = await _resolve_linked_or_complain(message, api)
    if linked is None:
        return
    data = await state.get_data()
    dr_id = data.get("dr_id", "")
    count = int(data.get("count", 0))
    if count == 0:
        await message.answer("Добавьте хотя бы один расход перед закрытием. /cancel — отмена.")
        return
    try:
        await api.close_detail_request(linked.user_id, dr_id)
    except APIError as e:
        logger.warning("close_detail_request failed: %s", e)
        await message.answer(f"Не удалось закрыть ЗнД (ошибка {e.status}).")
        return
    await state.clear()
    await message.answer(f"✅ ЗнД закрыт. Добавлено записей: {count}.")


@router.message(Command("dr_close"))
async def cmd_dr_close_idle(message: Message) -> None:
    """`/dr_close` outside the fill flow — gentle hint."""
    await message.answer("Сейчас нет заполняемого ЗнД. Откройте его командой /dr.")


@router.message(StateFilter(DraftStates.filling_detail_request), F.text)
async def on_dr_fill_text(
    message: Message,
    state: FSMContext,
    api: BudgetAPI,
    llm: AsyncOpenAI,
    llm_model: str,
) -> None:
    if message.from_user is None or not message.text:
        return
    linked = await _resolve_linked_or_complain(message, api)
    if linked is None:
        return
    await _add_dr_child(message, state, api, llm, llm_model, linked, message.text)


@router.message(StateFilter(DraftStates.filling_detail_request), F.voice | F.audio)
async def on_dr_fill_voice(
    message: Message,
    state: FSMContext,
    api: BudgetAPI,
    llm: AsyncOpenAI,
    llm_model: str,
    whisper: WhisperTranscriber | None,
) -> None:
    if whisper is None:
        await message.answer("🎤 Голосовой ввод временно недоступен.")
        return
    linked = await _resolve_linked_or_complain(message, api)
    if linked is None:
        return
    text = await _transcribe_voice(message, whisper)
    if text is None:
        return
    await _add_dr_child(message, state, api, llm, llm_model, linked, text)


async def _add_dr_child(
    message: Message,
    state: FSMContext,
    api: BudgetAPI,
    llm: AsyncOpenAI,
    llm_model: str,
    linked: LinkedUser,
    text: str,
) -> None:
    """Parse one expense line and append it as a child of the open ЗнД."""
    data = await state.get_data()
    dr_id = data.get("dr_id", "")
    try:
        ctx = await api.get_context(linked.user_id)
    except APIError:
        await message.answer("Не могу загрузить контекст, попробуйте позже.")
        return
    try:
        parsed = await parse_transaction(
            llm,
            model=llm_model,
            text=text,
            today=Date.today(),
            expense=ctx.expense,
            income=ctx.income,
            glossary=ctx.glossary,
            counterparties=ctx.counterparties,
        )
    except LLMParseError as e:
        await message.answer(f"⚠️ {escape(e.reason)} Пришлите запись ещё раз или /dr_close.")
        return
    # Force expense semantics — ЗнД children are always expenses.
    try:
        await api.add_detail_request_child(
            user_id=linked.user_id,
            dr_id=dr_id,
            amount=parsed.amount,
            category=parsed.category,
            purpose=parsed.counterparty,
            description=parsed.description,
            date_iso=parsed.date.isoformat(),
            deposit=parsed.deposit,
        )
    except APIError as e:
        logger.warning("add_detail_request_child failed: %s", e)
        await message.answer(f"Не удалось добавить запись (ошибка {e.status}).")
        return
    count = int(data.get("count", 0)) + 1
    await state.update_data(count=count)
    await message.answer(
        f"➕ {parsed.amount:g} ₽ · {escape(parsed.category)} (записей: {count}). "
        "Ещё или /dr_close."
    )


@router.message(F.voice | F.audio)
async def fallback_voice(
    message: Message,
    state: FSMContext,
    api: BudgetAPI,
    llm: AsyncOpenAI,
    llm_model: str,
    whisper: WhisperTranscriber | None,
) -> None:
    """Voice / audio → Whisper → text → intent router."""
    if whisper is None:
        await message.answer("🎤 Голосовой ввод временно недоступен.")
        return
    linked = await _resolve_linked_or_complain(message, api)
    if linked is None:
        return
    text = await _transcribe_voice(message, whisper)
    if text is None:
        return
    await _parse_and_confirm(message, state, api, llm, llm_model, linked, text)


@router.message(F.text)
async def fallback_text(
    message: Message,
    state: FSMContext,
    api: BudgetAPI,
    llm: AsyncOpenAI,
    llm_model: str,
) -> None:
    """Parse free-form text → show draft with confirm keyboard."""
    if message.from_user is None or not message.text:
        return
    linked = await _resolve_linked_or_complain(message, api)
    if linked is None:
        return
    await _parse_and_confirm(message, state, api, llm, llm_model, linked, message.text)


# ─── Inline-keyboard callbacks ────────────────────────────────────────────
#
# Every callback ends with `cb.answer()` so Telegram clears the loading
# spinner on the button. The original message is edited in place rather than
# replaced — keeps the chat compact and avoids stacked drafts.


def _format_result_line(p: ParsedTransaction) -> str:
    sign = "+" if p.type == "income" else "−"
    return f"✅ Сохранено: <b>{sign}{p.amount:g} ₽</b> ({escape(p.category)})"


@router.callback_query(StateFilter(DraftStates.awaiting_confirm), F.data == CB_CONFIRM)
async def on_confirm(cb: CallbackQuery, state: FSMContext, api: BudgetAPI) -> None:
    """Push the draft to /api/transactions; on success replace the keyboard
    with a one-liner confirmation; on backend error keep the draft so the
    user can retry."""
    if cb.from_user is None or cb.message is None:
        await cb.answer()
        return
    try:
        linked = await api.lookup_user(cb.from_user.id)
    except APIError:
        await cb.answer("Бэкенд недоступен", show_alert=True)
        return
    if linked is None:
        await cb.answer("Аккаунт не привязан", show_alert=True)
        return

    data = await state.get_data()
    draft = draft_from_dict(data["draft"])

    try:
        await api.create_transaction(
            user_id=linked.user_id,
            tx_type=draft.type,
            amount=draft.amount,
            category=draft.category,
            counterparty=draft.counterparty,
            date_iso=draft.date.isoformat(),
            description=draft.description,
            deposit=draft.deposit,
            wishlist_id=draft.wishlist_id,
        )
    except APIError as e:
        logger.warning("create_transaction failed: %s", e)
        await cb.answer(f"Ошибка {e.status} — попробуйте ещё раз", show_alert=True)
        return

    await state.clear()
    await cb.message.edit_text(_format_result_line(draft))
    await cb.answer("Сохранено")


@router.callback_query(StateFilter(DraftStates.awaiting_confirm), F.data == CB_CANCEL)
async def on_cancel(cb: CallbackQuery, state: FSMContext) -> None:
    if cb.message is None:
        await cb.answer()
        return
    await state.clear()
    await cb.message.edit_text("❌ Отменено.")
    await cb.answer()


@router.callback_query(StateFilter(DraftStates.awaiting_confirm), F.data == CB_EDIT_AMOUNT)
async def on_edit_amount(cb: CallbackQuery, state: FSMContext) -> None:
    if cb.message is None:
        await cb.answer()
        return
    await state.set_state(DraftStates.awaiting_amount)
    await cb.message.answer(
        "Пришлите новую сумму одним сообщением. /cancel — отмена."
    )
    await cb.answer()


@router.callback_query(StateFilter(DraftStates.awaiting_confirm), F.data == CB_EDIT_CATEGORY)
async def on_edit_category(cb: CallbackQuery, state: FSMContext, api: BudgetAPI) -> None:
    if cb.from_user is None or cb.message is None:
        await cb.answer()
        return
    try:
        linked = await api.lookup_user(cb.from_user.id)
        if linked is None:
            await cb.answer("Аккаунт не привязан", show_alert=True)
            return
        ctx = await api.get_context(linked.user_id)
    except APIError:
        await cb.answer("Бэкенд недоступен", show_alert=True)
        return

    data = await state.get_data()
    draft = draft_from_dict(data["draft"])
    options = ctx.expense_names() if draft.type == "expense" else ctx.income_names()
    if not options:
        await cb.answer("Нет категорий", show_alert=True)
        return
    await state.set_state(DraftStates.awaiting_category)
    await cb.message.edit_text(
        _format_parsed(draft) + "\n\n<b>Выберите категорию:</b>",
        reply_markup=_category_keyboard(options),
    )
    await cb.answer()


@router.callback_query(StateFilter(DraftStates.awaiting_category), F.data.startswith(CB_PICK_CATEGORY))
async def on_pick_category(cb: CallbackQuery, state: FSMContext) -> None:
    if cb.message is None or cb.data is None:
        await cb.answer()
        return
    new_cat = cb.data[len(CB_PICK_CATEGORY):]
    data = await state.get_data()
    draft = draft_from_dict(data["draft"])
    new = ParsedTransaction(
        type=draft.type,
        amount=draft.amount,
        category=new_cat,
        counterparty=draft.counterparty,
        date=draft.date,
        description=draft.description,
        deposit=draft.deposit,
    )
    await state.set_state(DraftStates.awaiting_confirm)
    await state.set_data({"draft": draft_to_dict(new)})
    await cb.message.edit_text(_format_parsed(new), reply_markup=_confirm_keyboard())
    await cb.answer()


@router.callback_query(StateFilter(DraftStates.awaiting_category), F.data == CB_BACK_TO_CONFIRM)
async def on_back_to_confirm(cb: CallbackQuery, state: FSMContext) -> None:
    if cb.message is None:
        await cb.answer()
        return
    data = await state.get_data()
    draft = draft_from_dict(data["draft"])
    await state.set_state(DraftStates.awaiting_confirm)
    await cb.message.edit_text(_format_parsed(draft), reply_markup=_confirm_keyboard())
    await cb.answer()


# ─── Wishlist confirm callbacks ─────────────────────────────────────────────


@router.callback_query(StateFilter(DraftStates.awaiting_wishlist_confirm), F.data == CB_WL_CONFIRM)
async def on_wl_confirm(cb: CallbackQuery, state: FSMContext, api: BudgetAPI) -> None:
    if cb.from_user is None or cb.message is None:
        await cb.answer()
        return
    try:
        linked = await api.lookup_user(cb.from_user.id)
    except APIError:
        await cb.answer("Бэкенд недоступен", show_alert=True)
        return
    if linked is None:
        await cb.answer("Аккаунт не привязан", show_alert=True)
        return

    data = await state.get_data()
    wl = wishlist_from_dict(data["wishlist"])
    try:
        await api.create_wishlist(
            user_id=linked.user_id,
            name=wl.name,
            estimated_cost=wl.estimated_cost,
            category=wl.category,
            notes=wl.notes,
        )
    except APIError as e:
        logger.warning("create_wishlist failed: %s", e)
        await cb.answer(f"Ошибка {e.status} — попробуйте ещё раз", show_alert=True)
        return

    await state.clear()
    await cb.message.edit_text(f"✅ В желания добавлено: <b>{escape(wl.name)}</b>")
    await cb.answer("Добавлено")


@router.callback_query(StateFilter(DraftStates.awaiting_wishlist_confirm), F.data == CB_WL_CANCEL)
async def on_wl_cancel(cb: CallbackQuery, state: FSMContext) -> None:
    if cb.message is None:
        await cb.answer()
        return
    await state.clear()
    await cb.message.edit_text("❌ Отменено.")
    await cb.answer()


# ─── Link-existing confirm callbacks ────────────────────────────────────────


@router.callback_query(StateFilter(DraftStates.awaiting_link_confirm), F.data == CB_LINK_CONFIRM)
async def on_link_confirm(cb: CallbackQuery, state: FSMContext, api: BudgetAPI) -> None:
    if cb.from_user is None or cb.message is None:
        await cb.answer()
        return
    try:
        linked = await api.lookup_user(cb.from_user.id)
    except APIError:
        await cb.answer("Бэкенд недоступен", show_alert=True)
        return
    if linked is None:
        await cb.answer("Аккаунт не привязан", show_alert=True)
        return

    data = await state.get_data()
    tx_id = data.get("tx_id", "")
    wl_id = data.get("wl_id", "")
    try:
        await api.link_expense(linked.user_id, wl_id, tx_id)
    except APIError as e:
        logger.warning("link_expense failed: %s", e)
        await cb.answer(f"Ошибка {e.status} — попробуйте ещё раз", show_alert=True)
        return

    await state.clear()
    await cb.message.edit_text("✅ Расход привязан.")
    await cb.answer("Привязано")


@router.callback_query(StateFilter(DraftStates.awaiting_link_confirm), F.data == CB_LINK_CANCEL)
async def on_link_cancel(cb: CallbackQuery, state: FSMContext) -> None:
    if cb.message is None:
        await cb.answer()
        return
    await state.clear()
    await cb.message.edit_text("❌ Отменено.")
    await cb.answer()


# ─── ЗнД create confirm callbacks ───────────────────────────────────────────


@router.callback_query(
    StateFilter(DraftStates.awaiting_dr_create_confirm), F.data == CB_DR_CREATE_CONFIRM
)
async def on_dr_create_confirm(cb: CallbackQuery, state: FSMContext, api: BudgetAPI) -> None:
    """Create the lump-sum parent expense, then open the detail-request over it
    and assign it. Two backend calls — if the second fails the expense stays
    (the user can open a ЗнД on it manually from the app)."""
    if cb.from_user is None or cb.message is None:
        await cb.answer()
        return
    try:
        linked = await api.lookup_user(cb.from_user.id)
    except APIError:
        await cb.answer("Бэкенд недоступен", show_alert=True)
        return
    if linked is None:
        await cb.answer("Аккаунт не привязан", show_alert=True)
        return

    data = await state.get_data()
    try:
        tx = await api.create_transaction(
            user_id=linked.user_id,
            tx_type="expense",
            amount=float(data["amount"]),
            category=str(data["category"]),
            counterparty=str(data.get("purpose") or ""),
            date_iso=Date.today().isoformat(),
            deposit=str(data.get("deposit") or "bank"),
        )
        await api.create_detail_request(
            linked.user_id, tx["id"], str(data["assignee_id"])
        )
    except APIError as e:
        logger.warning("dr create failed: %s", e)
        await cb.answer(f"Ошибка {e.status} — попробуйте ещё раз", show_alert=True)
        return

    await state.clear()
    await cb.message.edit_text(
        f"✅ ЗнД на <b>{float(data['amount']):g} ₽</b> создан и назначен на "
        f"<b>{escape(str(data.get('assignee_name') or ''))}</b>."
    )
    await cb.answer("Создано")


@router.callback_query(
    StateFilter(DraftStates.awaiting_dr_create_confirm), F.data == CB_DR_CREATE_CANCEL
)
async def on_dr_create_cancel(cb: CallbackQuery, state: FSMContext) -> None:
    if cb.message is None:
        await cb.answer()
        return
    await state.clear()
    await cb.message.edit_text("❌ Отменено.")
    await cb.answer()
