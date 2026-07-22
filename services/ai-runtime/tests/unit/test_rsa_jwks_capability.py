import base64
import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import jwt
import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from opsmind_ai_runtime.application.rsa_jwks_capability import RsaJwksCapabilityVerifier

ISSUER = "https://platform.example.test"
AUDIENCE = "opsmind-ai-runtime"
KEY_ID = "analysis-key-2026-07"


def _encoded_integer(value: int) -> str:
    size = max(1, (value.bit_length() + 7) // 8)
    return base64.urlsafe_b64encode(value.to_bytes(size, "big")).rstrip(b"=").decode()


def _key_material() -> tuple[rsa.RSAPrivateKey, str]:
    private_key = rsa.generate_private_key(public_exponent=65_537, key_size=2_048)
    numbers = private_key.public_key().public_numbers()
    jwks = json.dumps(
        {
            "keys": [
                {
                    "kty": "RSA",
                    "kid": KEY_ID,
                    "use": "sig",
                    "alg": "RS256",
                    "n": _encoded_integer(numbers.n),
                    "e": _encoded_integer(numbers.e),
                }
            ]
        }
    )
    return private_key, jwks


def _claims(*, audience: str = AUDIENCE) -> dict[str, object]:
    now = datetime.now(UTC)
    return {
        "iss": ISSUER,
        "sub": "operator:integration",
        "aud": audience,
        "iat": int((now - timedelta(seconds=1)).timestamp()),
        "exp": int((now + timedelta(minutes=1)).timestamp()),
        "jti": "nonce-rsa-1234567890",
        "tenant_id": str(uuid4()),
        "incident_id": str(uuid4()),
        "run_id": str(uuid4()),
        "purpose": "incident_investigation",
        "allowed_data_classes": ["redacted_metrics"],
        "request_digest": "sha256:" + "a" * 64,
    }


def _token(private_key: rsa.RSAPrivateKey, claims: dict[str, object]) -> str:
    return jwt.encode(
        claims,
        private_key,
        algorithm="RS256",
        headers={"kid": KEY_ID, "typ": "JWT"},
    )


def _signed_raw_token(
    private_key: rsa.RSAPrivateKey,
    header: str,
    claims: str,
) -> str:
    encoded_header = base64.urlsafe_b64encode(header.encode()).rstrip(b"=").decode()
    encoded_claims = base64.urlsafe_b64encode(claims.encode()).rstrip(b"=").decode()
    signing_input = f"{encoded_header}.{encoded_claims}"
    signature = private_key.sign(
        signing_input.encode("ascii"),
        padding.PKCS1v15(),
        hashes.SHA256(),
    )
    encoded_signature = base64.urlsafe_b64encode(signature).rstrip(b"=").decode()
    return f"{signing_input}.{encoded_signature}"


def test_rs256_jwks_verifier_accepts_exact_platform_contract() -> None:
    private_key, jwks = _key_material()
    verifier = RsaJwksCapabilityVerifier(
        jwks,
        expected_issuer=ISSUER + "/",
        expected_audience=AUDIENCE,
    )

    capability = verifier.verify(_token(private_key, _claims()))

    assert capability is not None
    assert capability.issuer == ISSUER
    assert capability.audience == AUDIENCE
    assert capability.nonce == "nonce-rsa-1234567890"


@pytest.mark.parametrize("duplicate", ["header", "claims"])
def test_rs256_verifier_rejects_signed_duplicate_json_members(duplicate: str) -> None:
    private_key, jwks = _key_material()
    header = f'{{"alg":"RS256","kid":"{KEY_ID}","typ":"JWT"}}'
    claims = json.dumps(_claims(), separators=(",", ":"))
    if duplicate == "header":
        header = header.replace(f'"kid":"{KEY_ID}"', f'"kid":"ignored","kid":"{KEY_ID}"')
    else:
        claims = claims.replace(f'"iss":"{ISSUER}"', f'"iss":"ignored","iss":"{ISSUER}"')
    verifier = RsaJwksCapabilityVerifier(
        jwks,
        expected_issuer=ISSUER,
        expected_audience=AUDIENCE,
    )

    assert verifier.verify(_signed_raw_token(private_key, header, claims)) is None


@pytest.mark.parametrize("mutation", ["audience", "kid", "claim", "signature"])
def test_rs256_jwks_verifier_rejects_wrong_scope_or_tampering(mutation: str) -> None:
    private_key, jwks = _key_material()
    claims = _claims(audience="other-audience" if mutation == "audience" else AUDIENCE)
    if mutation == "claim":
        claims["unexpected"] = "confused-deputy"
    encoded_capability = _token(private_key, claims)
    if mutation == "kid":
        encoded_capability = jwt.encode(
            claims,
            private_key,
            algorithm="RS256",
            headers={"kid": "unknown-key", "typ": "JWT"},
        )
    if mutation == "signature":
        encoded_header, encoded_payload, encoded_signature = encoded_capability.split(".")
        replacement = "A" if encoded_signature[0] != "A" else "B"
        encoded_capability = (
            f"{encoded_header}.{encoded_payload}.{replacement}{encoded_signature[1:]}"
        )

    verifier = RsaJwksCapabilityVerifier(
        jwks,
        expected_issuer=ISSUER,
        expected_audience=AUDIENCE,
    )
    assert verifier.verify(encoded_capability) is None


def test_jwks_rejects_duplicate_or_weak_keys() -> None:
    _, valid_jwks = _key_material()
    key = json.loads(valid_jwks)["keys"][0]
    duplicate = json.dumps({"keys": [key, key]})
    with pytest.raises(ValueError, match="metadata"):
        RsaJwksCapabilityVerifier(
            duplicate,
            expected_issuer=ISSUER,
            expected_audience=AUDIENCE,
        )

    weak_key = rsa.generate_private_key(public_exponent=65_537, key_size=1_024)
    numbers = weak_key.public_key().public_numbers()
    weak_jwks = json.dumps(
        {
            "keys": [
                {
                    "kty": "RSA",
                    "kid": KEY_ID,
                    "use": "sig",
                    "alg": "RS256",
                    "n": _encoded_integer(numbers.n),
                    "e": _encoded_integer(numbers.e),
                }
            ]
        }
    )
    with pytest.raises(ValueError, match="2048"):
        RsaJwksCapabilityVerifier(
            weak_jwks,
            expected_issuer=ISSUER,
            expected_audience=AUDIENCE,
        )

    invalid_scalar = json.loads(valid_jwks)
    invalid_scalar["keys"][0]["n"] = None
    with pytest.raises(ValueError, match="metadata"):
        RsaJwksCapabilityVerifier(
            json.dumps(invalid_scalar),
            expected_issuer=ISSUER,
            expected_audience=AUDIENCE,
        )


def test_jwks_rejects_duplicate_json_members_and_placeholder_trust_config() -> None:
    _, valid_jwks = _key_material()
    duplicate_member = valid_jwks.replace('"kty": "RSA"', '"kty": "RSA", "kty": "RSA"')
    with pytest.raises(ValueError, match="invalid"):
        RsaJwksCapabilityVerifier(
            duplicate_member,
            expected_issuer=ISSUER,
            expected_audience=AUDIENCE,
        )

    with pytest.raises(ValueError, match="routable"):
        RsaJwksCapabilityVerifier(
            valid_jwks,
            expected_issuer="https://platform.invalid.example",
            expected_audience=AUDIENCE,
        )
