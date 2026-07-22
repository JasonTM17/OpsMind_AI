"""Privacy-safe Problem Details helpers shared by HTTP routes."""

import re
from uuid import uuid4

from fastapi.responses import JSONResponse

from opsmind_ai_runtime.domain.analysis_contracts import ProblemDetails

_SAFE_CORRELATION_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


def resolve_correlation_id(value: str | None) -> str:
    return value if value is not None and _SAFE_CORRELATION_ID.fullmatch(value) else str(uuid4())


def build_problem_response(status_code: int, code: str, correlation_id: str) -> JSONResponse:
    body = ProblemDetails(
        title="Analysis request rejected",
        status=status_code,
        code=code,
        correlation_id=correlation_id,
    )
    return JSONResponse(
        status_code=status_code,
        content=body.model_dump(mode="json"),
        headers={"X-Correlation-ID": correlation_id},
        media_type="application/problem+json",
    )
