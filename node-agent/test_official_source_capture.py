"""Offline verification for allowlisted, bounded documentation capture."""
import hashlib
import io
import unittest
import urllib.error
from unittest.mock import patch

from official_source_capture import (
    APPROVED, MAX_BYTES, CaptureFailure, ApprovedSource,
    _RejectRedirects, build_capture_manifest, capture_one, validate_source,
)


class FakeResponse:
    def __init__(self, content=b"<html><body>" + b"manual content " * 8 + b"</body></html>",
                 mime="text/html", url=None, status=200, encoding="identity"):
        self.data = io.BytesIO(content)
        self.url = url
        self.status = status
        self.headers = {"Content-Type": mime, "Content-Encoding": encoding}

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.data.close()

    def read(self, size):
        return self.data.read(size)

    def geturl(self):
        return self.url


def fake(source_key="roblox_blender", **options):
    source = APPROVED[source_key]
    options.setdefault("url", source.url)
    return lambda req, timeout: FakeResponse(**options)


class SourceCaptureTests(unittest.TestCase):
    def test_real_body_digest_and_unassessed_status(self):
        content = b"<html>" + b"FBX import " * 10 + b"</html>"
        doc = capture_one(APPROVED["roblox_blender"], opener=fake(content=content), now_ms=42)
        self.assertEqual(doc["sha256"], hashlib.sha256(content).hexdigest())
        self.assertEqual(doc["bytes"], len(content))
        self.assertFalse(doc["inspected"])
        self.assertEqual(doc["assessment"], "UNASSESSED")
        self.assertFalse(doc["experiment_performed"])

    def test_only_preapproved_identical_https_urls_accepted(self):
        for url in (
            "https://127.0.0.1/secrets",
            "http://create.roblox.com/docs/art/blender",
            "https://create.roblox.com.evil.com/docs/art/blender",
            "https://create.roblox.com@127.0.0.1/docs/art/blender",
            "https://create.roblox.com:8080/docs/art/blender",
            "https://create.roblox.com/docs/art/blender?url=http://127.0.0.1",
            "https://create.roblox.com/docs/art/blender#sample",
        ):
            with self.subTest(url=url), self.assertRaises(CaptureFailure):
                validate_source(ApprovedSource("roblox_blender", "Roblox Creator Hub: Blender integration", url))

    def test_redirect_refused(self):
        with self.assertRaises(CaptureFailure):
            _RejectRedirects().redirect_request(None, None, 302, "Found", {}, "https://other.invalid")
        with self.assertRaises(CaptureFailure):
            capture_one(
                APPROVED["roblox_blender"],
                opener=fake(url="https://evil.invalid/redirect"),
            )

    def test_unexpected_mime_and_gzip_are_refused(self):
        for data in ({"mime": "application/octet-stream"}, {"encoding": "gzip"}):
            with self.subTest(**data), self.assertRaises(CaptureFailure):
                capture_one(APPROVED["roblox_blender"], opener=fake(**data))

    def test_does_not_read_unbounded_response(self):
        size = MAX_BYTES + 1
        class BoundedResponse(FakeResponse):
            def read(self, n):
                self.asserted_limit = n
                if n != size:
                    raise AssertionError("Unbounded read")
                return b"x" * size
        source = APPROVED["roblox_blender"]
        with self.assertRaisesRegex(CaptureFailure, "hard byte limit"):
            capture_one(source, opener=lambda req, timeout: BoundedResponse(url=source.url))

    def test_missing_and_truncated_docs_fail(self):
        for options in ({"status": 404}, {"content": b"tiny"}):
            with self.subTest(**options), self.assertRaises(CaptureFailure):
                capture_one(APPROVED["roblox_blender"], opener=fake(**options))

    def test_network_error_is_a_failure_not_a_fake_capture(self):
        def refuses(*args):
            raise urllib.error.URLError("offline")
        with self.assertRaises(CaptureFailure):
            capture_one(APPROVED["roblox_blender"], opener=refuses)

    def test_manifest_has_exact_requested_source_and_no_claims(self):
        manifest = build_capture_manifest(
            ["roblox_blender", "blender_fbx", "roblox_blender"],
            opener=lambda req, timeout: FakeResponse(url=req.full_url),
            now_ms=20261012
        )
        self.assertEqual(len(manifest["records"]), 2)
        self.assertEqual(manifest["errors"], [])
        self.assertFalse(manifest["automatic_verification"])
        self.assertTrue(all(not r["inspected"] for r in manifest["records"]))
        self.assertTrue(all(r["assessment"] == "UNASSESSED" for r in manifest["records"]))
        self.assertTrue(all("content" not in r for r in manifest["records"]))

    def test_manifest_partial_outage_is_reported(self):
        def partial(req, timeout):
            if "blender.org" in req.full_url:
                raise urllib.error.URLError("offline")
            return FakeResponse(url=req.full_url)
        manifest = build_capture_manifest(["roblox_export", "blender_fbx"], opener=partial)
        self.assertEqual(len(manifest["records"]), 1)
        self.assertEqual(manifest["errors"][0]["source_key"], "blender_fbx")

    def test_reject_unlisted_and_too_many_keys(self):
        with self.assertRaises(ValueError):
            build_capture_manifest(["evil"])
        with self.assertRaises(ValueError):
            build_capture_manifest([])
        with patch("official_source_capture.MAX_RECORDS", 1):
            with self.assertRaises(ValueError):
                build_capture_manifest(["roblox_blender", "blender_fbx"])


if __name__ == "__main__":
    unittest.main()
