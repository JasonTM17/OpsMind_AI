# syntax=docker/dockerfile:1.7
FROM ghcr.io/astral-sh/uv:0.11.29@sha256:eb2843a1e56fd9e30c7276ce1a52cba86e64c7b385f5e3279a0e08e02dd058fc AS uv

FROM python:3.14-slim-bookworm@sha256:86f975aca15cf04a40b399eebede9aea7c82eae084d1f1a0a6ef6bcaae871a30 AS build
COPY --from=uv /uv /uvx /bin/
ENV UV_COMPILE_BYTECODE=1
ENV UV_LINK_MODE=copy
ENV UV_PYTHON_DOWNLOADS=0
WORKDIR /app
COPY services/ai-runtime/pyproject.toml pyproject.toml
COPY services/ai-runtime/uv.lock uv.lock
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked --no-dev --no-install-project
COPY services/ai-runtime/src src
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked --no-dev --no-editable

FROM python:3.14-slim-bookworm@sha256:86f975aca15cf04a40b399eebede9aea7c82eae084d1f1a0a6ef6bcaae871a30 AS runtime
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1
ENV PATH="/app/.venv/bin:$PATH"
WORKDIR /app
RUN groupadd --system --gid 10001 opsmind && \
    useradd --system --uid 10001 --gid opsmind --no-create-home opsmind
COPY --from=build --chown=opsmind:opsmind /app/.venv /app/.venv
USER opsmind
EXPOSE 8000
CMD ["uvicorn", "opsmind_ai_runtime.main:app", "--host", "0.0.0.0", "--port", "8000"]
