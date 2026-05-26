"""Voice transcription — local (faster-whisper in-process) or remote
(OpenAI-compatible /v1/audio/transcriptions).

Both implementations expose `transcribe(audio_path) -> str` and are returned
behind the `Transcriber` Protocol. `make_transcriber(settings)` is the
factory — empty WHISPER_BASE_URL → local; URL → remote. The handler code
doesn't care which is wired in.

Why the split: on the dev box we keep everything in one container; in prod
the RPi only carries the app stack, while the noisy ML services
(llama.cpp + faster-whisper-server) live on a desktop with a real GPU and
are shared over the LAN.
"""

from __future__ import annotations

import asyncio
import logging
import os
from pathlib import Path
from typing import Protocol

# HuggingFace's Xet chunked-storage transport stalls behind certain HTTP
# proxies (observed: model.bin downloads truncate ~50% through). Forcing
# the classic per-file downloader is slower but predictable. Must be set
# BEFORE importing huggingface_hub (which faster-whisper does at import
# time). Only relevant for the LOCAL transcriber — `RemoteTranscriber`
# never touches HF.
os.environ.setdefault("HF_HUB_DISABLE_XET", "1")
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "600")

import httpx  # noqa: E402

from .config import Settings  # noqa: E402

logger = logging.getLogger(__name__)


class Transcriber(Protocol):
    async def transcribe(self, audio_path: str) -> str: ...
    async def aclose(self) -> None: ...


class LocalTranscriber:
    """In-process faster-whisper. Heavyweight image (+~450 MB for `small`
    int8 model weights, cached in a named volume). Best for dev/desktop."""

    def __init__(
        self,
        model_size: str,
        *,
        device: str = "cpu",
        compute_type: str = "int8",
        language: str = "ru",
    ) -> None:
        # Lazy-import faster-whisper so the remote-only path can skip the
        # ~1 s ctranslate2 import + spare the ~150 MB dependency tree on
        # images that don't need it. The build still ships the package
        # (single wheel) but it's not exercised at runtime.
        from faster_whisper import WhisperModel

        cache_dir = os.environ.get("HF_HOME") or "/cache/huggingface"
        Path(cache_dir).mkdir(parents=True, exist_ok=True)
        logger.info(
            "loading whisper model=%s device=%s compute_type=%s cache=%s",
            model_size, device, compute_type, cache_dir,
        )
        self._language = language
        self._model = WhisperModel(
            model_size,
            device=device,
            compute_type=compute_type,
            download_root=cache_dir,
        )
        logger.info("whisper model ready")

    async def transcribe(self, audio_path: str) -> str:
        loop = asyncio.get_running_loop()
        segments, _info = await loop.run_in_executor(
            None,
            lambda: self._model.transcribe(
                audio_path,
                language=self._language,
                beam_size=1,
                vad_filter=True,
                condition_on_previous_text=False,
            ),
        )
        chunks = await asyncio.to_thread(lambda: [s.text.strip() for s in segments])
        return " ".join(c for c in chunks if c).strip()

    async def aclose(self) -> None:
        # No persistent resources — the model object is a plain Python obj.
        return None


class RemoteTranscriber:
    """OpenAI-compatible HTTP client. Talks to faster-whisper-server,
    whisper.cpp's `--openai-api` server, or any compatible endpoint.

    Latency dominated by network + model — on a LAN with GPU-backed
    faster-whisper, ~10-sec voice round-trips in ~1 s including upload.
    """

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        language: str = "ru",
        *,
        timeout: float = 60.0,
    ) -> None:
        # Trailing-slash normalize so callers can pass either form. The
        # final POST URL is built explicitly below — easier to reason about
        # than depending on httpx base-url join.
        self._base = base_url.rstrip("/")
        self._model = model
        self._language = language
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._client = httpx.AsyncClient(timeout=timeout, headers=headers)
        logger.info("remote whisper endpoint=%s model=%s", self._base, self._model)

    async def transcribe(self, audio_path: str) -> str:
        # Multipart upload — OpenAI's /audio/transcriptions takes a real
        # file part, not a base64 blob. Server-side decoders (ffmpeg /
        # pyav) handle the Telegram OGG/Opus container fine.
        with open(audio_path, "rb") as f:
            files = {"file": ("audio.ogg", f.read(), "audio/ogg")}
        data = {"model": self._model, "language": self._language}
        r = await self._client.post(
            f"{self._base}/audio/transcriptions",
            files=files,
            data=data,
        )
        if r.status_code != 200:
            raise RuntimeError(f"remote whisper {r.status_code}: {r.text[:200]}")
        payload = r.json()
        return str(payload.get("text", "")).strip()

    async def aclose(self) -> None:
        await self._client.aclose()


def make_transcriber(settings: Settings) -> Transcriber:
    """Choose local vs remote based on settings.whisper_base_url. Raises on
    init errors — caller wraps in try/except so the rest of the bot stays
    alive even if Whisper is misconfigured."""
    if settings.whisper_base_url:
        return RemoteTranscriber(
            base_url=settings.whisper_base_url,
            api_key=settings.whisper_api_key,
            model=settings.whisper_remote_model,
            language=settings.whisper_language,
        )
    return LocalTranscriber(
        settings.whisper_model,
        device=settings.whisper_device,
        compute_type=settings.whisper_compute_type,
        language=settings.whisper_language,
    )


# Backwards-compatible alias — existing handler imports kept working.
WhisperTranscriber = Transcriber
