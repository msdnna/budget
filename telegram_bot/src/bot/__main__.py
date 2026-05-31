from __future__ import annotations

import asyncio
import logging

from aiogram import Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.client.session.aiohttp import AiohttpSession
from aiogram.enums import ParseMode

from .api_client import BudgetAPI
from .commands import setup_bot_commands
from .config import get_settings
from .handlers import router
from .llm_client import make_llm_client
from .whisper_client import Transcriber, make_transcriber


async def main() -> None:
    settings = get_settings()
    logging.basicConfig(
        level=settings.log_level.upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    log = logging.getLogger("bot")
    log.info(
        "starting bot, api=%s llm=%s proxy=%s",
        settings.budget_api_url,
        settings.llm_base_url,
        "yes" if settings.https_proxy else "no",
    )

    api = BudgetAPI(settings.budget_api_url, settings.service_token)
    llm = make_llm_client(settings.llm_base_url, settings.llm_api_key)

    # Whisper init — local-mode loads a 250-700 MB model (3-5 s for small
    # int8); remote-mode is essentially free (just constructs httpx).
    # Wrapped so a misconfig (bad URL, wrong model name) gracefully disables
    # voice without crashing text-flow.
    whisper: Transcriber | None = None
    try:
        whisper = make_transcriber(settings)
        log.info(
            "whisper ready (%s)",
            "remote" if settings.whisper_base_url else "local",
        )
    except Exception:
        log.exception("failed to init whisper — voice input will be disabled")
    # aiogram → Telegram traffic goes through HTTPS_PROXY when set. Intra-stack
    # calls (BudgetAPI / LLM) stay direct because httpx honours NO_PROXY.
    session = AiohttpSession(proxy=settings.https_proxy) if settings.https_proxy else None
    bot = Bot(
        token=settings.telegram_bot_token,
        default=DefaultBotProperties(parse_mode=ParseMode.HTML),
        session=session,
    )

    # Drop pending updates on startup — for an MVP single-instance bot we
    # don't want commands queued during downtime to fire stale state.
    dp = Dispatcher()
    dp.include_router(router)
    dp["api"] = api  # injected into handlers via the Dispatcher data dict
    dp["llm"] = llm
    dp["llm_model"] = settings.llm_model
    dp["whisper"] = whisper

    # Publish the «/» autocomplete menu + commands menu-button before polling.
    # Non-fatal: a transient Telegram error here shouldn't block message
    # handling, so we log and continue.
    try:
        await setup_bot_commands(bot)
        log.info("bot commands menu published")
    except Exception:
        log.exception("failed to publish bot commands menu")

    try:
        await dp.start_polling(bot, allowed_updates=dp.resolve_used_update_types())
    finally:
        await api.aclose()
        await llm.close()
        if whisper is not None:
            await whisper.aclose()
        await bot.session.close()


if __name__ == "__main__":
    asyncio.run(main())
