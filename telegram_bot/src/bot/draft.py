"""FSM state + draft serialization for the confirm flow.

aiogram's MemoryStorage round-trips arbitrary JSON-serializable dicts;
we lean on that instead of pickling pydantic models so the storage stays
trivially swap-able for Redis later (Phase ≥ Pi deploy) without payload
migrations.
"""

from __future__ import annotations

from datetime import date as Date

from aiogram.fsm.state import State, StatesGroup

from .llm_client import ParsedTransaction

# Inline-keyboard callback prefixes. Kept short so callback_data stays within
# Telegram's 64-byte cap once amended with payload (category name, etc.).
CB_CONFIRM = "tx:ok"
CB_CANCEL = "tx:no"
CB_EDIT_AMOUNT = "tx:amt"
CB_EDIT_CATEGORY = "tx:cat"
CB_PICK_CATEGORY = "tx:pc:"  # +"<name>"
CB_BACK_TO_CONFIRM = "tx:back"


class DraftStates(StatesGroup):
    awaiting_confirm = State()
    awaiting_amount = State()
    awaiting_category = State()


def draft_to_dict(p: ParsedTransaction) -> dict:
    return {
        "type": p.type,
        "amount": p.amount,
        "category": p.category,
        "counterparty": p.counterparty,
        "date": p.date.isoformat(),
        "description": p.description,
    }


def draft_from_dict(d: dict) -> ParsedTransaction:
    return ParsedTransaction(
        type=d["type"],
        amount=float(d["amount"]),
        category=d["category"],
        counterparty=d.get("counterparty", ""),
        date=Date.fromisoformat(d["date"]),
        description=d.get("description", ""),
    )
