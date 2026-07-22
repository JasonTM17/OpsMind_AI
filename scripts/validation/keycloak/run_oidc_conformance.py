#!/usr/bin/env python3
"""Execute sensitive OIDC browser-flow steps without writing tokens to stdout."""

from __future__ import annotations

import argparse
import json
import os
import secrets
import sys
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request

from oidc_browser_flow import (
    HTTP_TIMEOUT_SECONDS,
    OidcBrowserFlow,
    RedirectReached,
    decode_jwt_payload,
    find_form,
    jwt_key_id,
    read_bounded,
    write_private_result,
)


def require_environment(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"Required process environment is missing: {name}")
    return value


def assert_claims(payload: dict, issuer: str, audience: str, require_mfa: bool) -> None:
    token_audience = payload.get("aud", [])
    if isinstance(token_audience, str):
        token_audience = [token_audience]
    amr = payload.get("amr")
    lifetime = payload.get("exp", 0) - payload.get("iat", 0)
    if payload.get("iss") != issuer or audience not in token_audience:
        raise RuntimeError("Keycloak token issuer or audience does not match the profile.")
    if not isinstance(payload.get("sub"), str) or not payload["sub"]:
        raise RuntimeError("Keycloak token subject is missing.")
    if not isinstance(amr, list) or "pwd" not in amr or (require_mfa and "mfa" not in amr):
        raise RuntimeError("Keycloak token AMR does not match the completed flow.")
    if not require_mfa and "mfa" in amr:
        raise RuntimeError("TOTP enrollment was incorrectly elevated to MFA.")
    if lifetime < 1 or lifetime > 300:
        raise RuntimeError("Keycloak access-token lifetime exceeds the profile.")


def assert_pkce_required(flow: OidcBrowserFlow) -> str:
    state = secrets.token_urlsafe(18)
    parameters = urlencode({
        "client_id": flow.client_id,
        "redirect_uri": flow.redirect_uri,
        "response_type": "code",
        "scope": "openid",
        "state": state,
        "nonce": secrets.token_urlsafe(18),
    })
    try:
        response = flow.new_opener().open(
            flow.issuer + "/protocol/openid-connect/auth?" + parameters,
            timeout=HTTP_TIMEOUT_SECONDS,
        )
        document = read_bounded(response).decode()
        find_form(document, "kc-form-login")
    except HTTPError as error:
        if error.code == 400:
            return "invalid_request"
    except RedirectReached as redirect:
        query = parse_qs(urlparse(redirect.url).query)
        if query.get("state") == [state] and query.get("error"):
            return query["error"][0]
    raise RuntimeError("The public browser client accepted an authorization request without PKCE.")


def run_profile(args) -> dict:
    login_credential = require_environment("OPSMIND_KEYCLOAK_TEST_PASSWORD")
    flow = OidcBrowserFlow(args.issuer, args.client_id, args.redirect_uri, args.ca_cert)
    pkce_error = assert_pkce_required(flow)
    direct_error = flow.expect_oauth_error(
        lambda: flow.token_request({
            "grant_type": "password",
            "client_id": args.client_id,
            "username": args.username,
            "password": login_credential,
        }),
        {"unauthorized_client"},
    )

    authorization = flow.password_authorization(args.password_only_username, login_credential)
    wrong_verifier_error = flow.expect_oauth_error(
        lambda: flow.exchange(
            authorization.callback_url,
            secrets.token_urlsafe(64),
            authorization.state,
            authorization.opener,
        ),
        {"invalid_grant"},
    )

    enrollment_tokens, totp_seed, enrollment_opener = flow.enroll_totp(
        args.username,
        login_credential,
        args.totp_algorithm,
    )
    enrollment_payload = decode_jwt_payload(enrollment_tokens["access_token"])
    assert_claims(enrollment_payload, args.issuer, args.expected_audience, require_mfa=False)
    flow.logout(enrollment_opener, enrollment_tokens["id_token"])
    logout_refresh_error = flow.expect_oauth_error(
        lambda: flow.token_request({
            "grant_type": "refresh_token",
            "client_id": args.client_id,
            "refresh_token": enrollment_tokens["refresh_token"],
        }),
        {"invalid_grant"},
    )

    wait_seconds = flow.seconds_until_next_totp()
    time.sleep(wait_seconds)
    used_otp = flow.totp(totp_seed, args.totp_algorithm)
    used_time_step = flow.totp_time_step()
    valid_tokens, _ = flow.login_with_totp(
        args.username,
        login_credential,
        totp_seed,
        args.totp_algorithm,
        used_otp,
    )
    valid_payload = decode_jwt_payload(valid_tokens["access_token"])
    assert_claims(valid_payload, args.issuer, args.expected_audience, require_mfa=True)
    replay_denial = flow.assert_totp_replay_denied(
        args.username,
        login_credential,
        used_otp,
        used_time_step,
    )
    time.sleep(flow.seconds_until_next_totp())
    revocation_tokens, _ = flow.login_with_totp(
        args.username,
        login_credential,
        totp_seed,
        args.totp_algorithm,
    )
    revocation_payload = decode_jwt_payload(revocation_tokens["access_token"])
    assert_claims(revocation_payload, args.issuer, args.expected_audience, require_mfa=True)
    if (
        not isinstance(valid_payload.get("sid"), str)
        or not isinstance(revocation_payload.get("sid"), str)
        or valid_payload["sid"] == revocation_payload["sid"]
    ):
        raise RuntimeError("Keycloak did not create independent refresh-token sessions.")
    return {
        "validAccessToken": valid_tokens["access_token"],
        "validRefreshToken": valid_tokens["refresh_token"],
        "revocationRefreshToken": revocation_tokens["refresh_token"],
        "noMfaAccessToken": enrollment_tokens["access_token"],
        "validKeyId": jwt_key_id(valid_tokens["access_token"]),
        "subject": valid_payload["sub"],
        "summary": {
            "pkceMissingDenied": pkce_error,
            "directGrantDenied": direct_error,
            "wrongVerifierDenied": wrong_verifier_error,
            "logoutRefreshDenied": logout_refresh_error,
            "enrollmentAmr": enrollment_payload["amr"],
            "validAmr": valid_payload["amr"],
            "audience": valid_payload["aud"],
            "tokenLifetimeSeconds": valid_payload["exp"] - valid_payload["iat"],
            "otpReplayDenied": replay_denial,
            "independentRefreshSessions": True,
        },
    }


def refresh_once_profile(args) -> dict:
    renewal_credential = require_environment("OPSMIND_KEYCLOAK_REFRESH_TOKEN")
    flow = OidcBrowserFlow(args.issuer, args.client_id, args.redirect_uri, args.ca_cert)
    tokens = flow.token_request({
        "grant_type": "refresh_token",
        "client_id": args.client_id,
        "refresh_token": renewal_credential,
    })
    payload = decode_jwt_payload(tokens["access_token"])
    assert_claims(payload, args.issuer, args.expected_audience, require_mfa=True)
    return {
        "validAccessToken": tokens["access_token"],
        "validRefreshToken": tokens["refresh_token"],
        "validKeyId": jwt_key_id(tokens["access_token"]),
        "summary": {
            "amr": payload["amr"],
            "audience": payload["aud"],
            "tokenLifetimeSeconds": payload["exp"] - payload["iat"],
        },
    }


def refresh_profile(args) -> dict:
    renewal_credential = require_environment("OPSMIND_KEYCLOAK_REFRESH_TOKEN")
    result = refresh_once_profile(args)
    flow = OidcBrowserFlow(args.issuer, args.client_id, args.redirect_uri, args.ca_cert)
    reused_refresh_error = flow.expect_oauth_error(
        lambda: flow.token_request({
            "grant_type": "refresh_token",
            "client_id": args.client_id,
            "refresh_token": renewal_credential,
        }),
        {"invalid_grant"},
    )
    result["summary"]["previousRefreshReuseDenied"] = reused_refresh_error
    return result


def revoke_profile(args) -> dict:
    renewal_credential = require_environment("OPSMIND_KEYCLOAK_REFRESH_TOKEN")
    flow = OidcBrowserFlow(args.issuer, args.client_id, args.redirect_uri, args.ca_cert)
    request = Request(
        flow.issuer + "/protocol/openid-connect/revoke",
        data=urlencode({
            "client_id": args.client_id,
            "token": renewal_credential,
            "token_type_hint": "refresh_token",
        }).encode(),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    response = flow.new_opener().open(request, timeout=HTTP_TIMEOUT_SECONDS)
    if response.status != 200:
        raise RuntimeError("Keycloak token revocation did not return success.")
    refresh_error = flow.expect_oauth_error(
        lambda: flow.token_request({
            "grant_type": "refresh_token",
            "client_id": args.client_id,
            "refresh_token": renewal_credential,
        }),
        {"invalid_grant"},
    )
    return {"summary": {"revocationStatus": 200, "refreshAfterRevocation": refresh_error}}


def assert_disabled(args) -> dict:
    login_credential = require_environment("OPSMIND_KEYCLOAK_TEST_PASSWORD")
    flow = OidcBrowserFlow(args.issuer, args.client_id, args.redirect_uri, args.ca_cert)
    _, document, callback, _, _ = flow.start(args.username, login_credential)
    if callback is not None or document is None:
        raise RuntimeError("A disabled Keycloak user reached an authorization callback.")
    find_form(document, "kc-form-login")
    return {"summary": {"disabledUserLoginDenied": True}}


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser()
    value.add_argument(
        "command",
        choices=("run", "refresh", "refresh-once", "revoke", "assert-disabled"),
    )
    value.add_argument("--issuer", required=True)
    value.add_argument("--client-id", required=True)
    value.add_argument("--redirect-uri", required=True)
    value.add_argument("--ca-cert", required=True, type=Path)
    value.add_argument("--result-file", required=True, type=Path)
    value.add_argument("--username", default="opsmind-mfa-user")
    value.add_argument("--password-only-username", default="opsmind-password-user")
    value.add_argument("--expected-audience", default="opsmind-platform-api")
    value.add_argument("--totp-algorithm", default="HmacSHA256")
    return value


def main() -> int:
    args = parser().parse_args()
    handlers = {
        "run": run_profile,
        "refresh": refresh_profile,
        "refresh-once": refresh_once_profile,
        "revoke": revoke_profile,
        "assert-disabled": assert_disabled,
    }
    try:
        result = handlers[args.command](args)
        write_private_result(args.result_file, result)
        print(f"Command={args.command} Result=PASS")
        return 0
    except Exception as error:  # Deliberately suppress token-bearing internals.
        print(f"Command={args.command} Result=FAIL ErrorType={type(error).__name__}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
