from __future__ import annotations

from dataclasses import dataclass, field

import httpx


@dataclass
class LinkedUser:
    user_id: str
    display_name: str
    avatar_url: str | None = None


@dataclass
class CategoryHint:
    """Category name + admin-curated keyword list. Empty `keywords` is fine —
    the prompt just won't carry a hint line for that category."""

    name: str
    keywords: list[str]


@dataclass
class Counterparty:
    """One (counterparty, category, type) pair from the user's transaction
    history. `count` lets the prompt show frequency for stronger pull."""

    counterparty: str
    category: str
    type: str  # "income" | "expense"
    count: int


@dataclass
class GlossaryItem:
    term: str
    meaning: str


@dataclass
class TelegramContext:
    """Single-RPC bundle returned by /api/telegram/context."""

    expense: list[CategoryHint]
    income: list[CategoryHint]
    glossary: list[GlossaryItem]
    counterparties: list[Counterparty]
    # Admin-tunable intent trigger phrases keyed by intent name. Empty until
    # the backend ships them (Phase 4); merged with built-in defaults in
    # intents.merge_triggers, so an empty dict here is harmless.
    intent_triggers: dict[str, list[str]] = field(default_factory=dict)

    def expense_names(self) -> list[str]:
        return [c.name for c in self.expense]

    def income_names(self) -> list[str]:
        return [c.name for c in self.income]


@dataclass
class Categories:
    """Per-section category names. Kept for any caller that doesn't need the
    full LLM context (e.g. inline category-picker keyboard)."""

    expense: list[str]
    income: list[str]


@dataclass
class RegularItem:
    """One recurring (regular) expense item from the forecast endpoint. Used to
    match a recurring-payment message and to inherit fields onto the created
    expense transaction."""

    id: str
    name: str
    category: str
    deposit: str
    notes: str
    frequency: str


class APIError(Exception):
    """Wraps non-2xx responses from the budget API with the status + body."""

    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"budget API {status}: {body}")
        self.status = status
        self.body = body


class BudgetAPI:
    """Thin async client over the budget REST API.

    All calls authenticate with the shared SERVICE_TOKEN; user-scoped calls
    additionally set `X-Act-As-User` so the backend records the transaction
    under the real user's UserInfo snapshot (created_by mirror).
    """

    def __init__(self, base_url: str, service_token: str, *, timeout: float = 10.0) -> None:
        self._base = base_url.rstrip("/")
        self._svc = service_token
        self._client = httpx.AsyncClient(
            base_url=self._base,
            timeout=timeout,
            headers={"X-Service-Token": self._svc},
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def confirm_link(self, code: str, telegram_user_id: int, telegram_username: str | None) -> None:
        """Bot confirms a link code on behalf of the user.

        Returns silently on success, raises APIError on 4xx/5xx. The backend
        rejects expired/invalid codes with 400.
        """
        r = await self._client.post(
            "/api/telegram/link/confirm",
            json={
                "code": code,
                "telegram_user_id": telegram_user_id,
                "telegram_username": telegram_username or "",
            },
        )
        if r.status_code != 200:
            raise APIError(r.status_code, r.text)

    async def create_transaction(
        self,
        *,
        user_id: str,
        tx_type: str,
        amount: float,
        category: str,
        counterparty: str,
        date_iso: str,
        description: str = "",
        deposit: str = "bank",
        wishlist_id: str = "",
    ) -> dict:
        """Create a transaction on behalf of the linked user.

        `counterparty` maps to `source` for income and `purpose` for expense,
        mirroring how the web/Android UI splits the field. `description` is
        free-form notes (Transaction.Description on the backend). `deposit`
        defaults to "bank" (matches the backend's `NormalizeDeposit` default).
        `wishlist_id`, when set, links the expense to a recurring-payment item.
        """
        body: dict = {
            "type": tx_type,
            "amount": amount,
            "date": date_iso,
            "category": category,
            "deposit": deposit,
        }
        if counterparty:
            if tx_type == "income":
                body["source"] = counterparty
            else:
                body["purpose"] = counterparty
        if description:
            body["description"] = description
        if wishlist_id:
            body["wishlist_id"] = wishlist_id

        r = await self._client.post(
            "/api/transactions",
            json=body,
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 201:
            raise APIError(r.status_code, r.text)
        return r.json()

    async def get_context(self, user_id: str) -> TelegramContext:
        """Pull the full LLM prompt context in one RPC. Errors propagate as
        APIError — the bot surfaces a generic "не могу достучаться до бэкенда"
        message rather than partially-populated context."""
        r = await self._client.get(
            "/api/telegram/context",
            params={"user_id": user_id},
        )
        if r.status_code != 200:
            raise APIError(r.status_code, r.text)
        data = r.json()

        def _cats(raw: list[dict] | None) -> list[CategoryHint]:
            return [
                CategoryHint(
                    name=str(c.get("name", "")),
                    keywords=list(c.get("keywords") or []),
                )
                for c in (raw or [])
            ]

        # intent_triggers: { "<intent>": ["phrase", ...] }. Tolerate absent /
        # malformed shapes — the bot merges with built-in defaults regardless.
        raw_triggers = data.get("intent_triggers") or {}
        intent_triggers: dict[str, list[str]] = {}
        if isinstance(raw_triggers, dict):
            for intent, phrases in raw_triggers.items():
                if isinstance(phrases, list):
                    intent_triggers[str(intent)] = [str(p) for p in phrases]

        return TelegramContext(
            expense=_cats(data.get("expense")),
            income=_cats(data.get("income")),
            glossary=[
                GlossaryItem(term=str(g.get("term", "")), meaning=str(g.get("meaning", "")))
                for g in (data.get("glossary") or [])
            ],
            counterparties=[
                Counterparty(
                    counterparty=str(c.get("counterparty", "")),
                    category=str(c.get("category", "")),
                    type=str(c.get("type", "")),
                    count=int(c.get("count", 0)),
                )
                for c in (data.get("counterparties") or [])
            ],
            intent_triggers=intent_triggers,
        )

    async def list_categories(self, user_id: str) -> Categories:
        """Fetch expense + income category names for the LLM prompt context.

        Goes through the act-as path so the response reflects what the user
        actually sees in their app (currently shared family-wide, but the
        plumbing already passes the right identity for any future per-user
        scoping).
        """
        r = await self._client.get(
            "/api/categories/all",
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 200:
            raise APIError(r.status_code, r.text)
        data = r.json()
        return Categories(
            expense=[c["name"] for c in (data.get("expense") or [])],
            income=[c["name"] for c in (data.get("income") or [])],
        )

    async def list_wishlist_categories(self, user_id: str) -> list[str]:
        """Fetch wishlist-section category names (needed to create a wishlist
        item, which requires a category from that section)."""
        r = await self._client.get(
            "/api/categories",
            params={"section": "wishlist"},
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 200:
            raise APIError(r.status_code, r.text)
        return [c["name"] for c in (r.json() or [])]

    async def create_wishlist(
        self,
        *,
        user_id: str,
        name: str,
        estimated_cost: float,
        category: str,
        notes: str = "",
        frequency: str = "once",
        deposit: str = "bank",
    ) -> dict:
        """Create a wishlist item (frequency=once → planned purchase)."""
        body: dict = {
            "name": name,
            "estimated_cost": estimated_cost,
            "category": category,
            "frequency": frequency,
            "deposit": deposit,
        }
        if notes:
            body["notes"] = notes
        r = await self._client.post(
            "/api/wishlist",
            json=body,
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 201:
            raise APIError(r.status_code, r.text)
        return r.json()

    async def list_wishlist(self, user_id: str) -> list[dict]:
        """All wishlist items (raw dicts) — used by link-existing to resolve the
        target item by name/category/frequency."""
        r = await self._client.get(
            "/api/wishlist",
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 200:
            raise APIError(r.status_code, r.text)
        return r.json() or []

    async def link_expense(self, user_id: str, wishlist_id: str, tx_id: str) -> dict:
        """Attach an existing expense to a wishlist/regular item."""
        r = await self._client.post(
            f"/api/wishlist/{wishlist_id}/link/{tx_id}",
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 200:
            raise APIError(r.status_code, r.text)
        return r.json()

    async def get_regular_items(self, user_id: str) -> list[RegularItem]:
        """Pull recurring expense items from the forecast endpoint. These are
        wishlist items with frequency != once, surfaced as `regular_items`."""
        r = await self._client.get(
            "/api/statistics/forecast",
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 200:
            raise APIError(r.status_code, r.text)
        data = r.json() or {}
        out: list[RegularItem] = []
        for it in data.get("regular_items") or []:
            out.append(
                RegularItem(
                    id=str(it.get("id", "")),
                    name=str(it.get("name", "")),
                    category=str(it.get("category", "")),
                    deposit=str(it.get("deposit", "") or "bank"),
                    notes=str(it.get("notes", "")),
                    frequency=str(it.get("frequency", "")),
                )
            )
        return out

    async def list_transactions(
        self,
        user_id: str,
        *,
        tx_type: str | None = None,
        category: str | None = None,
        from_date: str | None = None,
        to_date: str | None = None,
        limit: int = 50,
        unlinked: bool = False,
    ) -> list[dict]:
        """List transactions for the user (raw dicts). Used by the income
        template (recent income per category) and link-existing (unlinked
        expenses) flows."""
        params: dict = {"limit": limit}
        if tx_type:
            params["type"] = tx_type
        if category:
            params["category"] = category
        if from_date:
            params["from"] = from_date
        if to_date:
            params["to"] = to_date
        if unlinked:
            params["unlinked"] = "true"
        r = await self._client.get(
            "/api/transactions",
            params=params,
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 200:
            raise APIError(r.status_code, r.text)
        return r.json().get("data") or []

    async def list_family(self, user_id: str) -> list[dict]:
        """Family member list (UserInfo dicts) — used to resolve a detail-request
        assignee by display name."""
        r = await self._client.get(
            "/api/users",
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 200:
            raise APIError(r.status_code, r.text)
        return r.json() or []

    async def create_detail_request(
        self, user_id: str, transaction_id: str, assignee_id: str
    ) -> dict:
        """Open a detail-request over an expense, assigned to a family member."""
        r = await self._client.post(
            "/api/detail-requests",
            json={"transaction_id": transaction_id, "assignee_id": assignee_id},
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 201:
            raise APIError(r.status_code, r.text)
        return r.json()

    async def list_detail_requests(
        self, user_id: str, *, assignee_id: str = "me", status: str = "open"
    ) -> list[dict]:
        r = await self._client.get(
            "/api/detail-requests",
            params={"assignee_id": assignee_id, "status": status},
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 200:
            raise APIError(r.status_code, r.text)
        return r.json() or []

    async def add_detail_request_child(
        self,
        *,
        user_id: str,
        dr_id: str,
        amount: float,
        category: str,
        purpose: str = "",
        description: str = "",
        date_iso: str,
        deposit: str = "bank",
    ) -> dict:
        """Add a child expense to an open detail-request (assignee-only)."""
        body: dict = {
            "type": "expense",
            "amount": amount,
            "date": date_iso,
            "category": category,
            "deposit": deposit,
        }
        if purpose:
            body["purpose"] = purpose
        if description:
            body["description"] = description
        r = await self._client.post(
            f"/api/detail-requests/{dr_id}/transactions",
            json=body,
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 201:
            raise APIError(r.status_code, r.text)
        return r.json()

    async def close_detail_request(self, user_id: str, dr_id: str) -> dict:
        r = await self._client.post(
            f"/api/detail-requests/{dr_id}/close",
            headers={"X-Act-As-User": user_id},
        )
        if r.status_code != 200:
            raise APIError(r.status_code, r.text)
        return r.json()

    async def lookup_user(self, telegram_user_id: int) -> LinkedUser | None:
        """Resolve a telegram_user_id to a budget user. Returns None on 404."""
        r = await self._client.get(
            "/api/telegram/me",
            params={"telegram_user_id": telegram_user_id},
        )
        if r.status_code == 404:
            return None
        if r.status_code != 200:
            raise APIError(r.status_code, r.text)
        data = r.json()
        return LinkedUser(
            user_id=data["user_id"],
            display_name=data.get("display_name", ""),
            avatar_url=data.get("avatar_url") or None,
        )
