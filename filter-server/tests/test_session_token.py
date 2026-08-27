"""Asserts that a PIN change actually invalidates outstanding dashboard sessions.

The first test is the regression test: the service's comments claimed this already happened while
the signature covered only the expiry, so it did not.
"""
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import session_token as st

SERVER_TOKEN = "a" * 64


class PinBindingTests(unittest.TestCase):
    def test_changing_the_pin_invalidates_an_existing_session(self):
        token = st.create(SERVER_TOKEN, "1234")
        self.assertTrue(st.valid(token, SERVER_TOKEN, "1234"))
        # The handoff flow's whole purpose: the previous holder must lose access.
        self.assertFalse(st.valid(token, SERVER_TOKEN, "5678"))

    def test_reverting_the_pin_revalidates_only_that_pin(self):
        token = st.create(SERVER_TOKEN, "1234")
        self.assertFalse(st.valid(token, SERVER_TOKEN, "9999"))
        self.assertTrue(st.valid(token, SERVER_TOKEN, "1234"))

    def test_no_pin_set_means_no_session_is_valid(self):
        token = st.create(SERVER_TOKEN, "1234")
        self.assertFalse(st.valid(token, SERVER_TOKEN, None))
        self.assertFalse(st.valid(token, SERVER_TOKEN, ""))

    def test_cannot_mint_a_session_without_a_pin(self):
        self.assertEqual(st.create(SERVER_TOKEN, None), "")
        self.assertEqual(st.create(SERVER_TOKEN, ""), "")

    def test_binding_does_not_leak_the_pin(self):
        binding = st.pin_binding("1234")
        self.assertNotIn("1234", binding)
        self.assertEqual(len(binding), 64)


class ForgeryTests(unittest.TestCase):
    def test_a_different_server_token_cannot_verify(self):
        token = st.create(SERVER_TOKEN, "1234")
        self.assertFalse(st.valid(token, "b" * 64, "1234"))

    def test_the_expiry_cannot_be_extended_by_the_client(self):
        token = st.create(SERVER_TOKEN, "1234")
        expiry, _, signature = token.partition(".")
        forged = f"{int(expiry) + 999999}.{signature}"
        self.assertFalse(st.valid(forged, SERVER_TOKEN, "1234"))

    def test_expired_tokens_are_rejected(self):
        token = st.create(SERVER_TOKEN, "1234", now=time.time() - st.MAX_AGE_SECONDS - 10)
        self.assertFalse(st.valid(token, SERVER_TOKEN, "1234"))

    def test_malformed_tokens_are_rejected(self):
        for bad in ["", ".", "abc", "abc.def", "1234", "notanumber.deadbeef",
                    f"{int(time.time()) + 999}.", "..", None]:
            self.assertFalse(st.valid(bad, SERVER_TOKEN, "1234"), f"accepted {bad!r}")


class CookieParsingTests(unittest.TestCase):
    def test_extracts_our_cookie_from_a_crowded_header(self):
        header = f"other=1; {st.COOKIE_NAME}=thevalue; another=2"
        self.assertEqual(st.cookie_from_header(header), "thevalue")

    def test_returns_none_when_absent_or_empty(self):
        self.assertIsNone(st.cookie_from_header("other=1; another=2"))
        self.assertIsNone(st.cookie_from_header(""))
        self.assertIsNone(st.cookie_from_header(None))

    def test_is_not_confused_by_a_similarly_named_cookie(self):
        self.assertIsNone(st.cookie_from_header(f"x{st.COOKIE_NAME}=nope"))


if __name__ == "__main__":
    unittest.main()
