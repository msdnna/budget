# Slim two-stage build: builder installs deps into a venv, runtime copies the
# venv only. Keeps the final layer small and free of compilers.
FROM python:3.12-slim AS builder

ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /build
COPY pyproject.toml ./
COPY src ./src

RUN python -m venv /opt/venv && \
    /opt/venv/bin/pip install --upgrade pip && \
    /opt/venv/bin/pip install .


FROM python:3.12-slim

ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app
COPY --from=builder /opt/venv /opt/venv
COPY src ./src

# /cache/huggingface stores Whisper weights between runs — mounted as a
# named volume in compose so the model survives container rebuilds (200-700 MB
# download otherwise repeats every `make bot-up`).
RUN useradd --create-home --uid 10001 botuser && \
    mkdir -p /cache/huggingface && \
    chown -R botuser:botuser /app /cache
USER botuser

CMD ["python", "-m", "bot"]
