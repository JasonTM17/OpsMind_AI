"""Verification of platform-issued delegated capabilities."""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from threading import Lock
from typing import Protocol

from opsmind_ai_runtime.domain.analysis_contracts import AnalysisRequestV1, DelegatedCapability


class CapabilityVerifier(Protocol):
    """Trust-boundary port implemented by the platform integration."""

    def verify(self, token: str) -> DelegatedCapability | None:
        """Return verified claims or ``None`` for any invalid token."""


class NonceStore(Protocol):
    def consume(self, nonce: str, *, expires_at: datetime) -> bool:
        """Atomically consume a capability nonce once."""


class InMemoryNonceStore:
    """Process-local replay guard; durable deployments must supply a shared store."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._nonces: dict[str, datetime] = {}

    def consume(self, nonce: str, *, expires_at: datetime) -> bool:
        now = datetime.now(UTC)
        with self._lock:
            self._nonces = {key: expiry for key, expiry in self._nonces.items() if expiry > now}
            if nonce in self._nonces:
                return False
            self._nonces[nonce] = expires_at
            return True


class StaticCapabilityVerifier:
    """Deterministic verifier for local contract tests; never used as production auth."""

    def __init__(self, tokens: dict[str, DelegatedCapability]) -> None:
        self._tokens = dict(tokens)

    def verify(self, token: str) -> DelegatedCapability | None:
        return self._tokens.get(token)


class CapabilityError(ValueError):
    """Raised when delegated scope cannot authorize a request."""


def analysis_request_digest(request: AnalysisRequestV1) -> str:
    """Hash the exact normalized request authorized by the platform issuer."""

    payload = request.model_dump(mode="json")
    payload["data_classifications"] = sorted(payload["data_classifications"])
    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return f"sha256:{hashlib.sha256(canonical).hexdigest()}"


def validate_capability(
    capability: DelegatedCapability,
    request: AnalysisRequestV1,
    *,
    expected_issuer: str,
    expected_audience: str,
    max_lifetime: timedelta,
    now: datetime | None = None,
) -> None:
    """Ensure every authoritative request dimension matches signed claims."""

    current = now or datetime.now(UTC)
    if capability.issuer != expected_issuer or capability.audience != expected_audience:
        raise CapabilityError("delegated capability issuer or audience rejected")
    if capability.expires_at <= current or capability.issued_at > current:
        raise CapabilityError("delegated capability is expired or not yet valid")
    if capability.expires_at - capability.issued_at > max_lifetime:
        raise CapabilityError("delegated capability lifetime exceeds policy")
    if capability.tenant_id != request.tenant_id:
        raise CapabilityError("tenant scope mismatch")
    if capability.incident_id != request.incident_id or capability.run_id != request.run_id:
        raise CapabilityError("workload scope mismatch")
    if request.deadline_at > capability.expires_at:
        raise CapabilityError("request deadline exceeds delegated capability lifetime")
    if capability.purpose != request.purpose:
        raise CapabilityError("purpose mismatch")
    if capability.request_digest != analysis_request_digest(request):
        raise CapabilityError("request digest mismatch")
    if not request.data_classifications.issubset(capability.allowed_data_classes):
        raise CapabilityError("data-class scope exceeds delegated capability")
