"""FSM state + draft serialization for the confirm flow.

aiogram's MemoryStorage round-trips arbitrary JSON-serializable dicts;
we lean on that instead of pickling pydantic models so the storage stays
trivially swap-able for Redis later (Phase ≥ Pi deploy) without payload
migrations.
"""

from __future__ import annotations

from datetime import date as Date

from aiogram.fsm.state import State, StatesGroup

from .llm_client import ParsedTransaction, ParsedWishlist

# Inline-keyboard callback prefixes. Kept short so callback_data stays within
# Telegram's 64-byte cap once amended with payload (category name, etc.).
CB_CONFIRM = "tx:ok"
CB_CANCEL = "tx:no"
CB_EDIT_AMOUNT = "tx:amt"
CB_EDIT_CATEGORY = "tx:cat"
CB_PICK_CATEGORY = "tx:pc:"  # +"<name>"
CB_BACK_TO_CONFIRM = "tx:back"

# Wishlist-item confirm flow.
CB_WL_CONFIRM = "wl:ok"
CB_WL_CANCEL = "wl:no"

# Link-existing confirm flow.
CB_LINK_CONFIRM = "ln:ok"
CB_LINK_CANCEL = "ln:no"

# Detail-request (ЗнД) create + fill flows.
CB_DR_CREATE_CONFIRM = "dr:ok"
CB_DR_CREATE_CANCEL = "dr:no"
CB_DR_PICK = "dr:p:"  # +"<dr_id>"


class DraftStates(StatesGroup):
    awaiting_confirm = State()
    awaiting_amount = State()
    awaiting_category = State()
    awaiting_wishlist_confirm = State()
    awaiting_link_confirm = State()
    awaiting_dr_create_confirm = State()
    filling_detail_request = State()


def draft_to_dict(p: ParsedTransaction) -> dict:
    return {
        "type": p.type,
        "amount": p.amount,
        "category": p.category,
        "counterparty": p.counterparty,
        "date": p.date.isoformat(),
        "description": p.description,
        "deposit": p.deposit,
        "wishlist_id": p.wishlist_id,
        "wishlist_name": p.wishlist_name,
    }


def draft_from_dict(d: dict) -> ParsedTransaction:
    return ParsedTransaction(
        type=d["type"],
        amount=float(d["amount"]),
        category=d["category"],
        counterparty=d.get("counterparty", ""),
        date=Date.fromisoformat(d["date"]),
        description=d.get("description", ""),
        deposit=d.get("deposit", "bank"),
        wishlist_id=d.get("wishlist_id", ""),
        wishlist_name=d.get("wishlist_name", ""),
    )


def wishlist_to_dict(p: ParsedWishlist) -> dict:
    return {
        "name": p.name,
        "estimated_cost": p.estimated_cost,
        "category": p.category,
        "notes": p.notes,
    }


def wishlist_from_dict(d: dict) -> ParsedWishlist:
    return ParsedWishlist(
        name=d["name"],
        estimated_cost=float(d["estimated_cost"]),
        category=d["category"],
        notes=d.get("notes", ""),
    )
