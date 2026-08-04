"""Focused tests for callback state and ID-token nonce validation."""

from __future__ import annotations

import base64
import json
import unittest
from unittest.mock import Mock

from oidc_browser_flow import AuthorizationResult, OidcBrowserFlow


def encode_test_jwt(payload: dict) -> str:
    encoded = base64.urlsafe_b64encode(
        json.dumps(payload, separators=(",", ":")).encode()
    ).rstrip(b"=").decode()
    signature = base64.urlsafe_b64encode(b"signature").rstrip(b"=").decode()
    return f"e30.{encoded}.{signature}"


class OidcBrowserFlowSecurityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.flow = object.__new__(OidcBrowserFlow)
        self.flow.client_id = "opsmind-browser"
        self.flow.redirect_uri = "http://127.0.0.1:3000/auth/callback"
        self.flow._token_request_count = 0
        self.callback = self.flow.redirect_uri + "?code=authorization-code&state=expected-state"

    def test_state_tamper_is_rejected_before_token_request(self) -> None:
        self.flow.token_request = Mock(side_effect=AssertionError("token request must not run"))
        authorization = AuthorizationResult(
            self.callback,
            "verifier",
            "expected-state",
            "expected-nonce",
            object(),
        )

        result = self.flow.assert_state_tamper_denied(authorization)

        self.assertEqual("callback_state_mismatch", result)
        self.assertEqual(0, self.flow.token_request_count)
        self.flow.token_request.assert_not_called()

    def test_exact_nonce_returns_the_original_token_dictionary(self) -> None:
        tokens = {
            "access_token": "opaque-for-test",
            "id_token": encode_test_jwt({"nonce": "expected-nonce"}),
        }
        self.flow.token_request = Mock(return_value=tokens)

        result = self.flow.exchange(
            self.callback,
            "verifier",
            "expected-state",
            "expected-nonce",
            object(),
        )

        self.assertIs(tokens, result)
        self.flow.token_request.assert_called_once()

    def test_invalid_id_token_or_nonce_is_rejected(self) -> None:
        invalid_cases = (
            ({}, "expected-nonce"),
            ({"id_token": ""}, "expected-nonce"),
            ({"id_token": 42}, "expected-nonce"),
            ({"id_token": "malformed"}, "expected-nonce"),
            ({"id_token": ".eyJub25jZSI6ImV4cGVjdGVkLW5vbmNlIn0.signature"}, "expected-nonce"),
            ({"id_token": "invalid!.eyJub25jZSI6ImV4cGVjdGVkLW5vbmNlIn0.signature"}, "expected-nonce"),
            ({"id_token": "W10.eyJub25jZSI6ImV4cGVjdGVkLW5vbmNlIn0.signature"}, "expected-nonce"),
            ({"id_token": "e30.eyJub25jZSI6ImV4cGVjdGVkLW5vbmNlIn0."}, "expected-nonce"),
            ({"id_token": "e30.eyJub25jZSI6ImV4cGVjdGVkLW5vbmNlIn0.invalid!"}, "expected-nonce"),
            ({"id_token": encode_test_jwt({})}, "expected-nonce"),
            ({"id_token": encode_test_jwt({"nonce": 42})}, "expected-nonce"),
            ({"id_token": encode_test_jwt({"nonce": "different-nonce"})}, "expected-nonce"),
            ({"id_token": encode_test_jwt({"nonce": "expected-nonce"})}, ""),
            ({"id_token": encode_test_jwt({"nonce": "expected-nonce"})}, 42),
        )
        for tokens, expected_nonce in invalid_cases:
            with self.subTest(tokens=tokens, expected_nonce=expected_nonce):
                self.flow.token_request = Mock(return_value=tokens)
                with self.assertRaises(RuntimeError):
                    self.flow.exchange(
                        self.callback,
                        "verifier",
                        "expected-state",
                        expected_nonce,
                        object(),
                    )

    def test_transient_authorization_material_is_not_in_repr(self) -> None:
        authorization = AuthorizationResult(
            self.callback,
            "secret-verifier",
            "secret-state",
            "secret-nonce",
            object(),
        )

        representation = repr(authorization)

        self.assertNotIn("authorization-code", representation)
        self.assertNotIn("secret-", representation)


if __name__ == "__main__":
    unittest.main()
