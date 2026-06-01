"""Optional Sentry telemetry for the bot.

A blank SENTRY_DSN disables everything — init_sentry returns without touching
sentry_sdk, so the bot behaves identically where telemetry isn't configured.
"""

from __future__ import annotations

import logging
from importlib.metadata import PackageNotFoundError, version

from .config import Settings

log = logging.getLogger("bot")


def init_sentry(settings: Settings) -> None:
    """Initialise Sentry from settings. No-op when no DSN is configured.

    aiogram logs unhandled handler exceptions at ERROR level, which Sentry's
    default LoggingIntegration converts into events (with stack traces) — so we
    capture "hidden" handler errors without any aiogram-specific wiring. The
    stdlib/httpx integrations also trace outgoing LLM / budget-API calls.
    """
    if not settings.sentry_dsn:
        log.info("Sentry disabled (SENTRY_DSN not set)")
        return

    try:
        release = f"budget-bot@{version('budget-telegram-bot')}"
    except PackageNotFoundError:
        release = "budget-bot@unknown"

    import sentry_sdk

    sentry_sdk.init(
        dsn=settings.sentry_dsn,
        environment=settings.sentry_env,
        release=release,
        traces_sample_rate=settings.sentry_traces_sample_rate,
        attach_stacktrace=True,
    )
    log.info(
        "Sentry enabled (env=%s, release=%s, traces=%.2f)",
        settings.sentry_env,
        release,
        settings.sentry_traces_sample_rate,
    )
