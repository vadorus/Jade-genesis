from __future__ import annotations

import unittest
from unittest import mock
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import jade_node_runtime_core as core
from jade_node_runtime_core import DEFAULT_BIND_ADDRESS, normalize_bind_address


class RuntimeBindAddressTest(unittest.TestCase):
    def test_default_preserves_existing_all_interface_behavior(self) -> None:
        self.assertEqual(DEFAULT_BIND_ADDRESS, normalize_bind_address(None))
        self.assertEqual("0.0.0.0", normalize_bind_address("0.0.0.0"))

    def test_explicit_tailscale_ipv4_is_accepted(self) -> None:
        self.assertEqual("100.64.0.10", normalize_bind_address(" 100.64.0.10 "))

    def test_hostname_ipv6_and_multicast_are_rejected(self) -> None:
        for invalid in ("localhost", "::1", "224.0.0.1"):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    normalize_bind_address(invalid)

    def test_installed_launcher_arguments_are_accepted_exactly(self) -> None:
        with mock.patch("sys.argv", ["JadeNodeRuntime.exe", "--node-kind", "PC",
                                    "--bind-address", "100.64.0.10", "--channel", "stable"]):
            args = core.parse_args()
        self.assertEqual("PC", args.node_kind)
        self.assertEqual("100.64.0.10", args.bind_address)
        self.assertEqual("stable", args.channel)

    def run_main(self, address, server):
        # Synthetic config only; do not read/write the installed Home runtime.
        config = {"node_id": "pc-synthetic", "node_kind": "PC", "port": 0}
        argv = ["JadeNodeRuntime.exe", "--node-kind", "PC", "--channel", "stable"]
        if address is not None:
            argv += ["--bind-address", address]
        with mock.patch("sys.argv", argv), \
                mock.patch.object(core, "load_or_create_config", return_value=config), \
                mock.patch.object(core, "print_status"), \
                mock.patch.object(core, "log_event"), \
                mock.patch.object(core, "AsyncTaskStore"), \
                mock.patch.object(core, "make_handler", return_value=BaseHTTPRequestHandler), \
                mock.patch.object(core, "runtime_started"), \
                mock.patch.object(core, "runtime_stopping"), \
                mock.patch.object(core, "ThreadingHTTPServer", server):
            self.assertEqual(0, core.main())
        return config

    def test_main_really_binds_the_requested_loopback_address(self) -> None:
        observed = []

        class InspectServer(ThreadingHTTPServer):
            def serve_forever(self, poll_interval=0.5):
                # This is the actual bound OS socket, not just /health metadata.
                observed.append(self.socket.getsockname())

        config = self.run_main("127.0.0.1", InspectServer)
        self.assertEqual("127.0.0.1", observed[0][0])
        self.assertGreater(observed[0][1], 0)
        self.assertEqual("127.0.0.1", config["_effective_bind_address"])

    def test_main_keeps_the_legacy_default_for_existing_services(self) -> None:
        server = mock.Mock()
        self.run_main(None, server)
        self.assertEqual(("0.0.0.0", 0), server.call_args.args[0])

    def test_invalid_address_stops_before_config_or_listener(self) -> None:
        with mock.patch("sys.argv", ["JadeNodeRuntime.exe", "--bind-address", "::1"]), \
                mock.patch.object(core, "load_or_create_config") as config, \
                mock.patch.object(core, "ThreadingHTTPServer") as server:
            self.assertEqual(2, core.main())
        config.assert_not_called()
        server.assert_not_called()


if __name__ == "__main__":
    unittest.main()
