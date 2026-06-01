from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime config loaded from environment.

    `model_config` picks up the same `.env` the rest of the monorepo uses so
    devs can run the bot directly from the host (poetry/uv/python) without
    duplicating secrets — docker compose still injects the same keys.
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    telegram_bot_token: str = Field(alias="TELEGRAM_BOT_TOKEN")

    budget_api_url: str = Field(
        default="http://backend:8080",
        alias="BUDGET_API_URL",
        description="Base URL of the budget REST API, no trailing slash.",
    )
    service_token: str = Field(alias="SERVICE_TOKEN")

    llm_base_url: str = Field(
        default="http://host.docker.internal:8001/v1",
        alias="LLM_BASE_URL",
        description="OpenAI-compatible chat-completions endpoint.",
    )
    llm_api_key: str = Field(default="not-needed", alias="LLM_API_KEY")
    llm_model: str = Field(
        default="Qwen3.5-9B-UD-Q4_K_XL.gguf",
        alias="LLM_MODEL",
    )

    log_level: str = Field(default="INFO", alias="LOG_LEVEL")

    # Whisper config. Two modes, chosen by `whisper_base_url`:
    #
    #   1. LOCAL (default, `whisper_base_url` empty): faster-whisper загружает
    #      модель прямо в бот-контейнер. Простой dev-сетап. На RPi 4 годен
    #      только `tiny`/`base` — `small`+ упирается в CPU.
    #
    #   2. REMOTE (`whisper_base_url` задан, e.g.
    #      "http://192.168.x.x:8002/v1"): бот шлёт аудио по OpenAI-compatible
    #      `/v1/audio/transcriptions`. На той стороне — faster-whisper-server
    #      / whisper.cpp server / любой совместимый бэк. Используется на
    #      продакшене (приложение на RPi, Whisper на ПК с GPU).
    #
    # Local-only поля (`whisper_model`/`whisper_device`/`whisper_compute_type`)
    # игнорируются в remote-режиме; remote-only (`whisper_remote_model`/
    # `whisper_api_key`) игнорируются в local-режиме.

    whisper_base_url: str | None = Field(default=None, alias="WHISPER_BASE_URL")
    whisper_api_key: str = Field(default="not-needed", alias="WHISPER_API_KEY")
    whisper_remote_model: str = Field(default="Systran/faster-whisper-small", alias="WHISPER_REMOTE_MODEL")
    whisper_model: str = Field(default="small", alias="WHISPER_MODEL")
    whisper_device: str = Field(default="cpu", alias="WHISPER_DEVICE")  # cpu | cuda
    whisper_compute_type: str = Field(
        default="int8", alias="WHISPER_COMPUTE_TYPE"
    )  # int8 | int8_float16 | float16 | float32
    whisper_language: str = Field(default="ru", alias="WHISPER_LANGUAGE")

    @field_validator("whisper_base_url", mode="before")
    @classmethod
    def _empty_whisper_url_is_none(cls, v: object) -> object:
        if isinstance(v, str) and not v.strip():
            return None
        return v

    # Egress proxy for Telegram. aiohttp (under aiogram) does not honour
    # HTTPS_PROXY env var automatically — we pass it explicitly. httpx (used
    # by BudgetAPI / LLM clients) reads HTTP_PROXY/NO_PROXY natively, so the
    # intra-docker calls bypass the proxy via NO_PROXY in compose.
    #
    # docker compose interpolation can deliver an empty string (shell-set
    # but blank); normalize that to None so `if settings.https_proxy` stays
    # the single source of truth for "use a proxy".
    https_proxy: str | None = Field(default=None, alias="HTTPS_PROXY")

    @field_validator("https_proxy", mode="before")
    @classmethod
    def _empty_proxy_is_none(cls, v: object) -> object:
        if isinstance(v, str) and not v.strip():
            return None
        return v

    # Sentry (error tracking + performance). Empty DSN = telemetry disabled.
    # This is the bot's OWN project — compose maps host SENTRY_BOT_DSN onto the
    # container's SENTRY_DSN so it never collides with the backend's project.
    # The DSN host must sit in NO_PROXY (host.docker.internal already is) so the
    # SDK reaches the LAN Sentry directly rather than via the Telegram proxy.
    sentry_dsn: str | None = Field(default=None, alias="SENTRY_DSN")
    sentry_env: str = Field(default="development", alias="SENTRY_ENV")
    sentry_traces_sample_rate: float = Field(default=0.1, alias="SENTRY_TRACES_SAMPLE_RATE")

    @field_validator("sentry_dsn", mode="before")
    @classmethod
    def _empty_dsn_is_none(cls, v: object) -> object:
        if isinstance(v, str) and not v.strip():
            return None
        return v

    @field_validator("sentry_traces_sample_rate", mode="before")
    @classmethod
    def _blank_rate_is_default(cls, v: object) -> object:
        # docker-compose interpolation can deliver an empty string for an unset
        # var; treat that as "use the default" rather than a parse error.
        if isinstance(v, str) and not v.strip():
            return 0.1
        return v


def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
