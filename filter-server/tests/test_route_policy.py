"""Asserts the device-vs-guardian boundary, which used to live only in the Caddyfile.

The shape of these tests matters as much as the assertions: the table in route_policy.py is a
mirror of a matcher in filter-server/Caddyfile, and a mirror drifts. `test_device_route_table_is_
exactly_as_expected` pins the whole set, so widening it -- accidentally or by copy-paste -- fails
here rather than silently granting guardian authority to a token that ships inside the APK.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import route_policy as rp


class DeviceRouteTableTests(unittest.TestCase):
    def test_device_route_table_is_exactly_as_expected(self):
        # Change this only alongside the Caddyfile matcher, and only deliberately.
        self.assertEqual(rp.DEVICE_BEARER_ROUTES, frozenset({
            ("GET", "/dashboard-api/devices/*/settings"),
            ("GET", "/dashboard-api/pin/exists"),
            ("GET", "/dashboard-api/habits"),
            ("POST", "/dashboard-api/pin/verify"),
            ("POST", "/dashboard-api/habits/*/complete"),
            ("POST", "/dashboard-api/devices/*/installed-apps"),
            ("POST", "/dashboard-api/devices/*/app-info"),
            ("GET", "/dashboard-api/admin-password-sync"),
        }))

    def test_the_routes_devices_actually_call_are_permitted(self):
        for method, path in [
            ("GET", "/dashboard-api/devices/00000000-1111-2222-3333-444444444444/settings"),
            ("GET", "/dashboard-api/pin/exists"),
            ("GET", "/dashboard-api/habits"),
            ("POST", "/dashboard-api/pin/verify"),
            ("POST", "/dashboard-api/habits/abc123/complete"),
            ("POST", "/dashboard-api/devices/mac-uuid/installed-apps"),
            ("POST", "/dashboard-api/devices/mac-uuid/app-info"),
            ("GET", "/dashboard-api/admin-password-sync"),
        ]:
            self.assertEqual(
                rp.required_access(method, path), rp.DEVICE_BEARER_OK,
                f"{method} {path} must stay device-reachable or real clients break",
            )


class GuardianOnlyTests(unittest.TestCase):
    def test_the_pin_is_never_device_reachable(self):
        # The plaintext-PIN read is the highest-value target on this service.
        self.assertEqual(rp.required_access("GET", "/dashboard-api/pin"), rp.GUARDIAN_SESSION_REQUIRED)
        self.assertEqual(rp.required_access("POST", "/dashboard-api/pin"), rp.GUARDIAN_SESSION_REQUIRED)
        self.assertEqual(rp.required_access("DELETE", "/dashboard-api/pin"), rp.GUARDIAN_SESSION_REQUIRED)

    def test_authoring_routes_require_a_guardian(self):
        for method, path in [
            ("PATCH", "/dashboard-api/devices/x/settings"),
            ("DELETE", "/dashboard-api/devices/x"),
            ("POST", "/dashboard-api/rules"),
            ("PATCH", "/dashboard-api/rules/r1"),
            ("DELETE", "/dashboard-api/rules/r1"),
            ("POST", "/dashboard-api/habits"),
            ("PATCH", "/dashboard-api/habits/h1"),
            ("DELETE", "/dashboard-api/habits/h1"),
            ("GET", "/dashboard-api/devices"),
            ("POST", "/dashboard-api/habits/import-from-habitshare"),
        ]:
            self.assertEqual(
                rp.required_access(method, path), rp.GUARDIAN_SESSION_REQUIRED,
                f"{method} {path} must not be reachable with a device token",
            )

    def test_habitshare_credentials_are_guardian_only(self):
        # Deliberately stricter than the Caddyfile: this returns a third-party username AND
        # password in plaintext, and no device code path needs it. See route_policy's module doc.
        self.assertEqual(
            rp.required_access("GET", "/dashboard-api/habitshare-account"),
            rp.GUARDIAN_SESSION_REQUIRED,
        )

    def test_revoking_a_completion_is_guardian_only_even_though_reporting_one_is_not(self):
        # A device may claim a habit is done; only a guardian may take that back.
        self.assertEqual(
            rp.required_access("POST", "/dashboard-api/habits/h1/complete"), rp.DEVICE_BEARER_OK)
        self.assertEqual(
            rp.required_access("DELETE", "/dashboard-api/habits/h1/complete"),
            rp.GUARDIAN_SESSION_REQUIRED)
        self.assertEqual(
            rp.required_access("GET", "/dashboard-api/habits/h1/proof"),
            rp.GUARDIAN_SESSION_REQUIRED)


class WildcardTests(unittest.TestCase):
    def test_a_wildcard_matches_exactly_one_segment(self):
        # A greedy wildcard would let extra slashes in a device id reach a different route.
        self.assertEqual(
            rp.required_access("GET", "/dashboard-api/devices/a/b/settings"),
            rp.GUARDIAN_SESSION_REQUIRED)
        self.assertEqual(
            rp.required_access("GET", "/dashboard-api/devices//settings"),
            rp.GUARDIAN_SESSION_REQUIRED)

    def test_traversal_and_suffix_tricks_do_not_reach_a_device_route(self):
        for path in [
            "/dashboard-api/devices/x/settings/../../pin",
            "/dashboard-api/devices/x/settings-extra",
            "/dashboard-api/pin/exists/../pin",
            "/dashboard-api/habits/../pin",
        ]:
            self.assertEqual(
                rp.required_access("GET", path), rp.GUARDIAN_SESSION_REQUIRED,
                f"{path} must not resolve to a device-reachable route",
            )

    def test_method_is_part_of_the_match(self):
        # The same path with a different verb is a different route.
        self.assertEqual(rp.required_access("GET", "/dashboard-api/habits"), rp.DEVICE_BEARER_OK)
        self.assertEqual(rp.required_access("POST", "/dashboard-api/habits"), rp.GUARDIAN_SESSION_REQUIRED)
        self.assertEqual(rp.required_access("get", "/dashboard-api/habits"), rp.DEVICE_BEARER_OK)


class ScopeTests(unittest.TestCase):
    def test_non_dashboard_paths_are_not_governed(self):
        # Genuine device reporting routes are gated by the bearer alone, by design.
        for path in ["/alerts/tamper", "/integrity/checkin", "/device-logs/upload",
                     "/screenshot-classify", "/report-config", "/lockprofile/x"]:
            self.assertFalse(rp.governs(path), f"{path} should be outside this policy")

    def test_an_unclassified_dashboard_route_fails_closed(self):
        # Forgetting to classify a new route must deny a device, not grant it authority.
        self.assertTrue(rp.governs("/dashboard-api/some-future-route"))
        self.assertEqual(
            rp.required_access("GET", "/dashboard-api/some-future-route"),
            rp.GUARDIAN_SESSION_REQUIRED)


if __name__ == "__main__":
    unittest.main()
