"""Minimal browser-flow client for the isolated Keycloak conformance harness."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import ssl
import struct
import time
from dataclasses import dataclass
from html.parser import HTMLParser
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import (
    HTTPRedirectHandler,
    HTTPCookieProcessor,
    HTTPSHandler,
    Request,
    build_opener,
)

HTTP_TIMEOUT_SECONDS = 15
MAX_HTTP_BODY_BYTES = 2 * 1024 * 1024


class RedirectReached(Exception):
    def __init__(self, url: str) -> None:
        super().__init__("An expected loopback redirect was reached.")
        self.url = url


def redirect_matches(url: str, expected: str) -> bool:
    candidate = urlparse(url)
    target = urlparse(expected)
    return (
        candidate.scheme == target.scheme
        and candidate.netloc == target.netloc
        and candidate.path == target.path
        and not candidate.params
        and not candidate.fragment
    )


def issuer_contains(url: str, issuer: str) -> bool:
    candidate = urlparse(url)
    authority = urlparse(issuer)
    issuer_path = authority.path.rstrip("/")
    return (
        candidate.scheme == authority.scheme
        and candidate.netloc == authority.netloc
        and (candidate.path == issuer_path or candidate.path.startswith(issuer_path + "/"))
        and not candidate.fragment
    )


def read_bounded(response) -> bytes:
    value = response.read(MAX_HTTP_BODY_BYTES + 1)
    if len(value) > MAX_HTTP_BODY_BYTES:
        raise RuntimeError("Keycloak response exceeded the conformance body limit.")
    return value


def read_json_object(response) -> dict[str, Any]:
    value = json.loads(read_bounded(response))
    if not isinstance(value, dict):
        raise RuntimeError("Keycloak JSON response is not an object.")
    return value


class RedirectCapture(HTTPRedirectHandler):
    def __init__(self, issuer: str, targets: tuple[str, ...]) -> None:
        super().__init__()
        self.issuer = issuer
        self.targets = targets

    def redirect_request(self, request, response, code, message, headers, url):
        if any(redirect_matches(url, target) for target in self.targets):
            raise RedirectReached(url)
        if not issuer_contains(url, self.issuer):
            raise RuntimeError("OIDC redirect left the pinned issuer boundary.")
        return super().redirect_request(request, response, code, message, headers, url)


class KeycloakFormParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.forms: list[dict[str, Any]] = []
        self.current: dict[str, Any] | None = None

    def handle_starttag(self, tag: str, attributes) -> None:
        values = dict(attributes)
        if tag == "form":
            self.current = {
                "id": values.get("id"),
                "action": values.get("action", ""),
                "inputs": {},
            }
        elif tag == "input" and self.current is not None and values.get("name"):
            self.current["inputs"][values["name"]] = values.get("value", "")

    def handle_endtag(self, tag: str) -> None:
        if tag == "form" and self.current is not None:
            self.forms.append(self.current)
            self.current = None


@dataclass(frozen=True)
class AuthorizationResult:
    callback_url: str
    verifier: str
    state: str
    opener: Any


def parse_forms(document: str) -> list[dict[str, Any]]:
    parser = KeycloakFormParser()
    parser.feed(document)
    return parser.forms


def find_form(document: str, form_id: str) -> dict[str, Any]:
    for form in parse_forms(document):
        if form["id"] == form_id:
            return form
    raise RuntimeError(f"Expected Keycloak form was not rendered: {form_id}")


def decode_jwt_payload(token: str) -> dict[str, Any]:
    parts = token.split(".")
    if len(parts) != 3:
        raise RuntimeError("Keycloak returned a malformed JWT.")
    payload = parts[1] + "=" * (-len(parts[1]) % 4)
    decoded = json.loads(base64.urlsafe_b64decode(payload))
    if not isinstance(decoded, dict):
        raise RuntimeError("Keycloak JWT payload is not an object.")
    return decoded


def jwt_key_id(token: str) -> str:
    header = token.split(".")[0]
    decoded = json.loads(base64.urlsafe_b64decode(header + "=" * (-len(header) % 4)))
    key_id = decoded.get("kid")
    if not isinstance(key_id, str) or not key_id:
        raise RuntimeError("Keycloak JWT does not contain a key identifier.")
    return key_id


def write_private_result(path: Path, value: dict[str, Any]) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        json.dump(value, output, separators=(",", ":"))


class OidcBrowserFlow:
    def __init__(self, issuer: str, client_id: str, redirect_uri: str, ca_cert: Path) -> None:
        self.issuer = issuer.rstrip("/")
        self.client_id = client_id
        self.redirect_uri = redirect_uri
        self.logout_uri = redirect_uri.rsplit("/", 1)[0] + "/logout"
        self.ssl_context = ssl.create_default_context(cafile=str(ca_cert))

    def new_opener(self):
        return build_opener(
            HTTPSHandler(context=self.ssl_context),
            HTTPCookieProcessor(CookieJar()),
            RedirectCapture(self.issuer, (self.redirect_uri, self.logout_uri)),
        )

    def start(self, username: str, password: str) -> tuple[Any, str | None, str, str, str]:
        verifier = self._url_token(48)
        challenge = self._b64(hashlib.sha256(verifier.encode()).digest())
        state = self._url_token(18)
        parameters = {
            "client_id": self.client_id,
            "redirect_uri": self.redirect_uri,
            "response_type": "code",
            "scope": "openid profile email",
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "state": state,
            "nonce": self._url_token(18),
            "prompt": "login",
        }
        opener = self.new_opener()
        response = opener.open(
            self.issuer + "/protocol/openid-connect/auth?" + urlencode(parameters),
            timeout=HTTP_TIMEOUT_SECONDS,
        )
        document = read_bounded(response).decode()
        form = find_form(document, "kc-form-login")
        fields = dict(form["inputs"])
        fields.update(username=username, password=password, credentialId="")
        next_document, callback, _ = self.post_form(opener, form["action"], fields)
        return opener, next_document, callback, verifier, state

    def enroll_totp(self, username: str, password: str, algorithm: str):
        opener, document, callback, verifier, state = self.start(username, password)
        if callback is not None or document is None:
            raise RuntimeError("TOTP enrollment was not required for the conformance user.")
        form = find_form(document, "kc-totp-settings-form")
        totp_seed = form["inputs"].get("totpSecret")
        if not isinstance(totp_seed, str) or len(totp_seed) < 16:
            raise RuntimeError("Keycloak did not provide a bounded TOTP enrollment secret.")
        fields = dict(form["inputs"])
        fields.update(totp=self.totp(totp_seed, algorithm), userLabel="OpsMind conformance")
        _, callback, _ = self.post_form(opener, form["action"], fields)
        if callback is None:
            raise RuntimeError("TOTP enrollment did not complete the authorization flow.")
        return self.exchange(callback, verifier, state, opener), totp_seed, opener

    def login_with_totp(
        self,
        username: str,
        password: str,
        totp_seed: str,
        algorithm: str,
        otp: str | None = None,
    ):
        opener, document, callback, verifier, state = self.start(username, password)
        if callback is not None or document is None:
            raise RuntimeError("Keycloak did not require the configured second factor.")
        form = find_form(document, "kc-otp-login-form")
        fields = dict(form["inputs"])
        fields["otp"] = self.totp(totp_seed, algorithm) if otp is None else otp
        _, callback, _ = self.post_form(opener, form["action"], fields)
        if callback is None:
            raise RuntimeError("The OTP challenge did not complete the authorization flow.")
        return self.exchange(callback, verifier, state, opener), opener

    def assert_totp_replay_denied(
        self,
        username: str,
        password: str,
        used_otp: str,
        used_time_step: int,
    ) -> str:
        if self.totp_time_step() != used_time_step:
            raise RuntimeError("TOTP replay check crossed its original timestep before replay.")
        opener, document, callback, _, _ = self.start(username, password)
        if callback is not None or document is None:
            raise RuntimeError("TOTP replay check did not reach the second-factor challenge.")
        form = find_form(document, "kc-otp-login-form")
        fields = dict(form["inputs"])
        fields["otp"] = used_otp
        denial_document, replay_callback, response_url = self.post_form(opener, form["action"], fields)
        if replay_callback is not None:
            raise RuntimeError("Keycloak accepted the same TOTP code twice in one timestep.")
        if denial_document is None or response_url is None:
            raise RuntimeError("Keycloak did not return a bounded TOTP replay denial page.")
        if self.totp_time_step() != used_time_step:
            raise RuntimeError("TOTP replay check crossed its original timestep during replay.")

        issuer = urlparse(self.issuer)
        response = urlparse(response_url)
        login_path = issuer.path.rstrip("/") + "/login-actions/"
        if (
            response.scheme != issuer.scheme
            or response.netloc != issuer.netloc
            or not response.path.startswith(login_path)
        ):
            raise RuntimeError("TOTP replay denial left the pinned Keycloak login boundary.")
        find_form(denial_document, "kc-otp-login-form")
        field_error = re.search(r'id=["\']input-error-(?:otp-code|otp)["\']', denial_document)
        invalid_otp = re.search(
            r'<input\b(?=[^>]*\bname=["\']otp["\'])(?=[^>]*\baria-invalid=["\']true["\'])',
            denial_document,
            re.IGNORECASE,
        )
        if field_error is None or invalid_otp is None:
            raise RuntimeError("Keycloak did not identify the replayed OTP field as invalid.")
        return "invalid_otp_same_timestep"

    def password_authorization(self, username: str, password: str) -> AuthorizationResult:
        opener, document, callback, verifier, state = self.start(username, password)
        if callback is None or document is not None:
            raise RuntimeError("Password-only authorization did not reach its callback.")
        return AuthorizationResult(callback, verifier, state, opener)

    def exchange(self, callback: str, verifier: str, state: str, opener):
        query = parse_qs(urlparse(callback).query)
        if query.get("state") != [state] or len(query.get("code", [])) != 1:
            raise RuntimeError("Authorization callback state or code is invalid.")
        return self.token_request({
            "grant_type": "authorization_code",
            "client_id": self.client_id,
            "redirect_uri": self.redirect_uri,
            "code": query["code"][0],
            "code_verifier": verifier,
        }, opener)

    def token_request(self, fields: dict[str, str], opener=None) -> dict[str, Any]:
        active_opener = opener or self.new_opener()
        request = Request(
            self.issuer + "/protocol/openid-connect/token",
            data=urlencode(fields).encode(),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        response = active_opener.open(request, timeout=HTTP_TIMEOUT_SECONDS)
        return read_json_object(response)

    def post_form(
        self,
        opener,
        action: str,
        fields: dict[str, str],
    ) -> tuple[str | None, str | None, str | None]:
        if not issuer_contains(action, self.issuer):
            raise RuntimeError("Keycloak form action left the pinned issuer boundary.")
        action_path = urlparse(action).path
        if not action_path.startswith(urlparse(self.issuer).path.rstrip("/") + "/login-actions/"):
            raise RuntimeError("Keycloak form action is outside the login-actions boundary.")
        request = Request(
            action,
            data=urlencode(fields).encode(),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        try:
            response = opener.open(request, timeout=HTTP_TIMEOUT_SECONDS)
            if not issuer_contains(response.geturl(), self.issuer):
                raise RuntimeError("Keycloak form response left the pinned issuer boundary.")
            document = read_bounded(response).decode()
            return document, None, response.geturl()
        except RedirectReached as redirect:
            return None, redirect.url, redirect.url

    def logout(self, opener, id_token: str) -> None:
        state = self._url_token(18)
        parameters = urlencode({
            "id_token_hint": id_token,
            "post_logout_redirect_uri": self.logout_uri,
            "state": state,
        })
        try:
            opener.open(
                self.issuer + "/protocol/openid-connect/logout?" + parameters,
                timeout=HTTP_TIMEOUT_SECONDS,
            )
        except RedirectReached as redirect:
            query = parse_qs(urlparse(redirect.url).query)
            if query.get("state") == [state] and redirect_matches(redirect.url, self.logout_uri):
                return
        raise RuntimeError("RP-initiated logout did not reach the bound loopback redirect.")

    @staticmethod
    def totp(totp_seed: str, algorithm: str) -> str:
        digest_name = {"HmacSHA1": "sha1", "HmacSHA256": "sha256", "HmacSHA512": "sha512"}.get(algorithm)
        if digest_name is None:
            raise RuntimeError("Unsupported Keycloak TOTP algorithm.")
        counter = OidcBrowserFlow.totp_time_step()
        digest = hmac.new(totp_seed.encode(), struct.pack(">Q", counter), digest_name).digest()
        offset = digest[-1] & 0x0F
        value = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
        return str(value % 1_000_000).zfill(6)

    @staticmethod
    def totp_time_step() -> int:
        return int(time.time()) // 30

    @staticmethod
    def seconds_until_next_totp() -> int:
        return 31 - (int(time.time()) % 30)

    @staticmethod
    def expect_oauth_error(callable_request, accepted: set[str]) -> str:
        try:
            callable_request()
        except HTTPError as error:
            try:
                value = read_json_object(error)
            except (json.JSONDecodeError, RuntimeError):
                value = {}
            code = value.get("error")
            if error.code == 400 and code in accepted:
                return code
        raise RuntimeError("Keycloak did not return the expected OAuth denial.")

    @staticmethod
    def _b64(value: bytes) -> str:
        return base64.urlsafe_b64encode(value).rstrip(b"=").decode()

    @classmethod
    def _url_token(cls, size: int) -> str:
        return cls._b64(secrets.token_bytes(size))
