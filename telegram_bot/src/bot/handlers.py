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

from .api_client import APIError, BudgetAPI, LinkedUser
from .draft import (
    CB_BACK_TO_CONFIRM,
    CB_CANCEL,
    CB_CONFIRM,
    CB_EDIT_AMOUNT,
    CB_EDIT_CATEGORY,
    CB_PICK_CATEGORY,
    DraftStates,
    draft_from_dict,
    draft_to_dict,
)
from .llm_client import LLMParseError, ParsedTransaction, parse_transaction
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
        "Пишите доход или расход в свободной форме, например:\n"
        "• <i>продукты магнит 2300</i>\n"
        "• <i>пришла зарплата 50000</i>\n"
        "• <i>вчера такси 450</i>\n\n"
        "Я предложу распарсенный вариант, его нужно будет подтвердить кнопкой.\n\n"
        "/unlink — отвязать аккаунт"
    )


def _format_parsed(p: ParsedTransaction) -> str:
    """Render the parsed draft for human review. HTML-escape user input —
    Telegram's HTML parse-mode would otherwise mangle `<`, `&`, etc."""
    title = "💰 Доход" if p.type == "income" else "💸 Расход"
    cp = escape(p.counterparty) if p.counterparty else "—"
    lines = [
        f"<b>{title}</b>",
        f"Сумма: <b>{p.amount:g} ₽</b>",
        f"Категория: <b>{escape(p.category)}</b>",
        f"Источник/назначение: {cp}",
        f"Дата: {p.date.isoformat()}",
    ]
    if p.description:
        lines.append(f"Детали: <i>{escape(p.description)}</i>")
    return "\n".join(lines)


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
    """Shared tail of the text-in / voice-in flows: load context, hit LLM,
    show the draft with the confirm keyboard. Voice handler delegates here
    after Whisper produces the transcript so the parsing logic stays single
    source of truth."""
    try:
        ctx = await api.get_context(linked.user_id)
    except APIError:
        logger.exception("get_context failed")
        await message.answer("Не могу загрузить контекст, попробуйте позже.")
        return

    await message.bot.send_chat_action(message.chat.id, "typing")

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

    await _enter_confirm(message, state, parsed)


@router.message(F.voice | F.audio)
async def fallback_voice(
    message: Message,
    state: FSMContext,
    api: BudgetAPI,
    llm: AsyncOpenAI,
    llm_model: str,
    whisper: WhisperTranscriber | None,
) -> None:
    """Voice / audio → Whisper → text → parse_transaction."""
    if whisper is None:
        await message.answer("🎤 Голосовой ввод временно недоступен.")
        return
    linked = await _resolve_linked_or_complain(message, api)
    if linked is None:
        return

    # `voice` (.ogg/opus) is Telegram's recorder format; `audio` covers any
    # uploaded audio file. Both expose `file_id` + `duration` — we just need
    # the id to download.
    file_id: str | None = None
    if message.voice is not None:
        file_id = message.voice.file_id
    elif message.audio is not None:
        file_id = message.audio.file_id
    if file_id is None:
        return

    await message.bot.send_chat_action(message.chat.id, "typing")

    # tempfile lives only for the duration of transcribe; cleanup is automatic
    # via the context manager. `.ogg` suffix is informational — faster-whisper
    # detects format from container, not extension.
    with tempfile.NamedTemporaryFile(suffix=".ogg", delete=False) as tmp:
        tmp_path = tmp.name
    try:
        tg_file = await message.bot.get_file(file_id)
        if tg_file.file_path is None:
            await message.answer("Не удалось получить голосовое сообщение.")
            return
        await message.bot.download_file(tg_file.file_path, destination=tmp_path)
        try:
            text = await whisper.transcribe(tmp_path)
        except Exception:
            logger.exception("whisper.transcribe failed")
            await message.answer(
                "Не удалось распознать аудио. Попробуйте написать текстом."
            )
            return
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass

    if not text:
        await message.answer(
            "🎤 Не услышал текст — возможно тишина или слишком шумно. Попробуйте ещё раз."
        )
        return

    # Echo what we heard so the user can spot misrecognitions before the
    # confirm screen — far cheaper than fixing a bad draft via the editor.
    await message.answer(f"🎤 <i>{escape(text)}</i>")
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
