"""Bounded strict JSON and key parsing for delegated capabilities."""

from __future__ import annotations

import base64
import binascii
import json
import re
from pathlib import Path
from typing import Final

import jwt

_MAX_JWKS_BYTES: Final = 262_144
_MAX_KEYS: Final = 16
_KID = re.compile(r"[A-Za-z0-9._-]{1,64}")
_BASE64URL = re.compile(r"[A-Za-z0-9_-]+")


def load_jwks_file(path: str) -> str:
    key_path = Path(path)
    if not key_path.is_file():
        raise ValueError("capability JWKS file is unavailable")
    with key_path.open("rb") as stream:
        raw = stream.read(_MAX_JWKS_BYTES + 1)
    if not raw or len(raw) > _MAX_JWKS_BYTES:
        raise ValueError("capability JWKS file size is invalid")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError("capability JWKS file must be UTF-8") from exc


def load_jwks_keys(jwks_json: str) -> dict[str, jwt.PyJWK]:
    if not jwks_json or len(jwks_json.encode("utf-8")) > _MAX_JWKS_BYTES:
        raise ValueError("capability JWKS document size is invalid")
    try:
        document = json.loads(jwks_json, object_pairs_hook=_reject_duplicate_keys)
    except (json.JSONDecodeError, UnicodeError, ValueError) as exc:
        raise ValueError("capability JWKS document is invalid") from exc
    if not isinstance(document, dict) or set(document) != {"keys"}:
        raise ValueError("capability JWKS must contain only keys")
    raw_keys = document["keys"]
    if not isinstance(raw_keys, list) or not 1 <= len(raw_keys) <= _MAX_KEYS:
        raise ValueError("capability JWKS key count is invalid")
    loaded: dict[str, jwt.PyJWK] = {}
    for raw_key in raw_keys:
        _load_jwk(raw_key, loaded)
    return loaded


def decode_jwt_object(segment: str) -> dict[str, object]:
    if not segment or _BASE64URL.fullmatch(segment) is None:
        raise ValueError("capability JWT segment is invalid")
    padding = b"=" * ((4 - len(segment) % 4) % 4)
    try:
        raw = base64.b64decode(
            segment.encode("ascii") + padding,
            altchars=b"-_",
            validate=True,
        )
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicate_keys)
    except (binascii.Error, json.JSONDecodeError, UnicodeError, ValueError) as exc:
        raise ValueError("capability JWT JSON is invalid") from exc
    if not isinstance(value, dict):
        raise ValueError("capability JWT segment must be an object")
    return value


def _load_jwk(raw_key: object, loaded: dict[str, jwt.PyJWK]) -> None:
    if not isinstance(raw_key, dict) or set(raw_key) != {"kty", "kid", "use", "alg", "n", "e"}:
        raise ValueError("capability JWK shape is invalid")
    kid = raw_key.get("kid")
    modulus = raw_key.get("n")
    exponent = raw_key.get("e")
    if (
        raw_key.get("kty") != "RSA"
        or raw_key.get("use") != "sig"
        or raw_key.get("alg") != "RS256"
        or not isinstance(kid, str)
        or _KID.fullmatch(kid) is None
        or kid in loaded
        or not isinstance(modulus, str)
        or not 1 <= len(modulus) <= 1_024
        or _BASE64URL.fullmatch(modulus) is None
        or not isinstance(exponent, str)
        or not 1 <= len(exponent) <= 16
        or _BASE64URL.fullmatch(exponent) is None
    ):
        raise ValueError("capability JWK metadata is invalid")
    try:
        key = jwt.PyJWK.from_dict(raw_key, algorithm="RS256")
    except (jwt.PyJWTError, TypeError, ValueError, OverflowError) as exc:
        raise ValueError("capability JWK key material is invalid") from exc
    if getattr(key.key, "key_size", 0) < 2_048:
        raise ValueError("capability RSA key must contain at least 2048 bits")
    loaded[kid] = key


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("capability JSON contains a duplicate member")
        result[name] = value
    return result
