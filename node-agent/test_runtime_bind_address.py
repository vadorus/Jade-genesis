from __future__ import annotations

import unittest

from jade_node_runtime_core import DEFAULT_BIND_ADDRESS, normalize_bind_address


class RuntimeBindAddressTest(unittest.TestCase):
    def test_default_preserves_existing_all_interface_behavior(self) -> None:
        self.assertEqual(DEFAULT_BIND_ADDRESS, normalize_bind_address(None))
        self.assertEqual("0.0.0.0", normalize_bind_address("0.0.0.0"))

    def test_explicit_tailscale_ipv4_is_accepted(self) -> None:
        self.assertEqual("100.98.238.6", normalize_bind_address(" 100.98.238.6 "))

    def test_hostname_ipv6_and_multicast_are_rejected(self) -> None:
        for invalid in ("localhost", "::1", "224.0.0.1"):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    normalize_bind_address(invalid)


if __name__ == "__main__":
    unittest.main()
