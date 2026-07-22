"""RS256 delegated-capability verification with an operator-owned JWKS file."""

from __future__ import annotations

import re
from datetime import UTC, datetime
from typing import Final
from urllib.parse import urlsplit

import jwt

from opsmind_ai_runtime.application.rsa_jwks_parser import (
    decode_jwt_object,
    load_jwks_file,
    load_jwks_keys,
)
from opsmind_ai_runtime.domain.analysis_contracts import DelegatedCapability

_MAX_TOKEN_BYTES: Final = 16_384
_AUDIENCE = re.compile(r"[A-Za-z0-9._-]{1,128}")
_HEADER_NAMES = frozenset({"alg", "kid", "typ"})
_CLAIM_NAMES = frozenset(
    {
        "iss",
        "sub",
        "aud",
        "iat",
        "exp",
        "jti",
        "tenant_id",
        "incident_id",
        "run_id",
        "purpose",
        "allowed_data_classes",
        "request_digest",
    }
)


class RsaJwksCapabilityVerifier:
    """Verify platform JWTs without remote key discovery or algorithm agility."""

    def __init__(self, jwks_json: str, *, expected_issuer: str, expected_audience: str) -> None:
        normalized_issuer = expected_issuer.rstrip("/")
        parsed_issuer = urlsplit(normalized_issuer)
        issuer_host = (parsed_issuer.hostname or "").lower()
        if (
            parsed_issuer.scheme != "https"
            or not issuer_host
            or parsed_issuer.username
            or parsed_issuer.password
            or parsed_issuer.query
            or parsed_issuer.fragment
            or issuer_host == "invalid.example"
            or issuer_host.endswith(".invalid.example")
        ):
            raise ValueError("capability issuer must be a routable HTTPS identifier")
        if _AUDIENCE.fullmatch(expected_audience) is None:
            raise ValueError("capability audience is invalid")
        self._issuer = normalized_issuer
        self._audience = expected_audience
        self._keys = load_jwks_keys(jwks_json)

    @classmethod
    def from_file(
        cls,
        path: str,
        *,
        expected_issuer: str,
        expected_audience: str,
    ) -> RsaJwksCapabilityVerifier:
        return cls(
            load_jwks_file(path),
            expected_issuer=expected_issuer,
            expected_audience=expected_audience,
        )

    def verify(self, token: str) -> DelegatedCapability | None:
        if not token or len(token.encode("utf-8")) > _MAX_TOKEN_BYTES or token.count(".") != 2:
            return None
        try:
            encoded_header, encoded_claims, _ = token.split(".")
            header = decode_jwt_object(encoded_header)
            unverified_claims = decode_jwt_object(encoded_claims)
            if set(header) != _HEADER_NAMES:
                return None
            kid = header.get("kid")
            if (
                header.get("alg") != "RS256"
                or header.get("typ") != "JWT"
                or not isinstance(kid, str)
            ):
                return None
            key = self._keys.get(kid)
            if key is None:
                return None
            claims = jwt.decode(
                token,
                key=key.key,
                algorithms=["RS256"],
                audience=self._audience,
                issuer=self._issuer,
                options={
                    "require": ["iss", "sub", "aud", "iat", "exp", "jti"],
                    "verify_signature": True,
                    "verify_exp": True,
                    "verify_iat": True,
                    "verify_aud": True,
                    "verify_iss": True,
                },
            )
            if (
                set(claims) != _CLAIM_NAMES
                or claims != unverified_claims
                or claims.get("aud") != self._audience
            ):
                return None
            return DelegatedCapability.model_validate(
                {
                    "issuer": claims["iss"],
                    "subject": claims["sub"],
                    "audience": claims["aud"],
                    "tenant_id": claims["tenant_id"],
                    "incident_id": claims["incident_id"],
                    "run_id": claims["run_id"],
                    "purpose": claims["purpose"],
                    "allowed_data_classes": claims["allowed_data_classes"],
                    "request_digest": claims["request_digest"],
                    "nonce": claims["jti"],
                    "issued_at": _timestamp(claims["iat"]),
                    "expires_at": _timestamp(claims["exp"]),
                }
            )
        except (
            jwt.PyJWTError,
            ValueError,
            TypeError,
            KeyError,
            UnicodeError,
            OverflowError,
            OSError,
        ):
            return None


def _timestamp(value: object) -> datetime:
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValueError("capability timestamp is invalid")
    return datetime.fromtimestamp(value, UTC)
