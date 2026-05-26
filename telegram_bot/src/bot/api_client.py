from __future__ import annotations

from dataclasses import dataclass

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
    ) -> dict:
        """Create a transaction on behalf of the linked user.

        `counterparty` maps to `source` for income and `purpose` for expense,
        mirroring how the web/Android UI splits the field. `description` is
        free-form notes (Transaction.Description on the backend). `deposit`
        defaults to "bank" (matches the backend's `NormalizeDeposit` default).
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
