"""Versioned analysis endpoint with delegated-capability enforcement."""

from __future__ import annotations

from typing import Protocol

from fastapi import APIRouter, Header, Response, status
from fastapi.responses import JSONResponse

from opsmind_ai_runtime.api.problem_details import build_problem_response, resolve_correlation_id
from opsmind_ai_runtime.application.analysis_service import AnalysisFailure
from opsmind_ai_runtime.domain.analysis_contracts import (
    AnalysisRequestV1,
    AnalysisResponseV1,
)


class AnalysisExecutor(Protocol):
    async def analyze(
        self,
        request: AnalysisRequestV1,
        *,
        capability_token: str,
    ) -> AnalysisResponseV1: ...


def build_analysis_router(service: AnalysisExecutor) -> APIRouter:
    router = APIRouter(prefix="/api/v1", tags=["analysis"])

    @router.post(
        "/analysis",
        response_model=AnalysisResponseV1,
        status_code=status.HTTP_200_OK,
    )
    async def analyze(
        request: AnalysisRequestV1,
        response: Response,
        x_opsmind_delegated_capability: str | None = Header(default=None),
        x_correlation_id: str | None = Header(default=None),
    ) -> AnalysisResponseV1 | JSONResponse:
        correlation_id = resolve_correlation_id(x_correlation_id)
        response.headers["X-Correlation-ID"] = correlation_id
        if not x_opsmind_delegated_capability:
            return build_problem_response(401, "delegation.invalid", correlation_id)
        try:
            return await service.analyze(request, capability_token=x_opsmind_delegated_capability)
        except AnalysisFailure as exc:
            error_status = {
                "budget.exceeded": 429,
                "delegation.invalid": 403,
                "delegation.unavailable": 503,
                "egress.denied": 403,
                "request.deadline_exceeded": 408,
                "runtime.state_unavailable": 503,
                "runtime.overloaded": 503,
                "provider.deadline_exceeded": 504,
                "provider.invalid_request": 502,
                "provider.invalid_response": 502,
                "provider.unauthorized": 502,
                "provider.insufficient_balance": 502,
                "provider.internal": 502,
                "provider.rate_limited": 503,
                "provider.unavailable": 503,
            }.get(exc.code, 503 if exc.code.startswith("provider.") else 500)
            return build_problem_response(error_status, exc.code, correlation_id)
        except Exception as exc:
            _ = exc
            return build_problem_response(500, "runtime.unexpected_failure", correlation_id)

    return router
