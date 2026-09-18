import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from ffmpeg_capability_probe import (
    DURATION_SECONDS,
    HEIGHT,
    OPERATION,
    PROBE_ID,
    PROVIDER_ID,
    WIDTH,
    ffmpeg_probe_status,
    run_ffmpeg_transcode_probe,
)


class FfmpegCapabilityProbeTest(unittest.TestCase):

    def test_status_requires_ffmpeg_and_ffprobe(self):
        paths = {
            "ffmpeg": "/fake/ffmpeg",
            "ffprobe": "/fake/ffprobe",
        }
        status = ffmpeg_probe_status(paths.get)

        self.assertTrue(status["ready"])
        self.assertEqual(PROVIDER_ID, status["provider_id"])
        self.assertEqual(OPERATION, status["operation"])
        self.assertFalse(status["user_file_access"])
        self.assertFalse(status["arbitrary_arguments_allowed"])
        self.assertFalse(status["shell_execution"])

    def test_missing_ffprobe_is_not_ready(self):
        status = ffmpeg_probe_status(
            lambda name: "/fake/ffmpeg" if name == "ffmpeg" else None
        )

        self.assertFalse(status["ready"])
        self.assertTrue(status["ffmpeg_present"])
        self.assertFalse(status["ffprobe_present"])

    def test_unknown_payload_fields_are_rejected(self):
        with self.assertRaisesRegex(
            ValueError,
            "ffmpeg_probe_unknown_fields",
        ):
            run_ffmpeg_transcode_probe(
                '{"profile":"%s","input":"/tmp/a.mp4"}' % PROBE_ID,
                which=lambda name: f"/fake/{name}",
                runner=lambda command, timeout: subprocess.CompletedProcess(
                    command,
                    0,
                    stdout="",
                    stderr="",
                ),
            )

    def test_unsupported_profile_is_rejected(self):
        with self.assertRaisesRegex(
            ValueError,
            "unsupported_ffmpeg_probe_profile",
        ):
            run_ffmpeg_transcode_probe(
                '{"profile":"arbitrary"}',
                which=lambda name: f"/fake/{name}",
                runner=lambda command, timeout: subprocess.CompletedProcess(
                    command,
                    0,
                    stdout="",
                    stderr="",
                ),
            )

    def test_successful_probe_uses_fixed_synthetic_input_and_verifies_output(self):
        observed_commands = []

        def fake_runner(command, timeout):
            observed_commands.append((list(command), timeout))
            executable = Path(command[0]).name
            if executable == "ffmpeg":
                output = Path(command[-1])
                output.write_bytes(b"jade-ffmpeg-probe")
                return subprocess.CompletedProcess(
                    command,
                    0,
                    stdout="",
                    stderr="",
                )
            if executable == "ffprobe":
                payload = {
                    "streams": [
                        {
                            "codec_name": "mpeg4",
                            "width": WIDTH,
                            "height": HEIGHT,
                            "r_frame_rate": "10/1",
                            "duration": str(DURATION_SECONDS),
                        }
                    ],
                    "format": {
                        "duration": str(DURATION_SECONDS),
                        "size": "18",
                    },
                }
                return subprocess.CompletedProcess(
                    command,
                    0,
                    stdout=json.dumps(payload),
                    stderr="",
                )
            raise AssertionError(f"unexpected executable: {executable}")

        ticks = iter([1_000_000_000, 1_075_000_000])
        result_text, duration_ms = run_ffmpeg_transcode_probe(
            "",
            which=lambda name: f"/fake/{name}",
            runner=fake_runner,
            clock_ns=lambda: next(ticks),
        )
        result = json.loads(result_text)

        self.assertEqual(75, duration_ms)
        self.assertTrue(result["success"])
        self.assertTrue(result["verification_passed"])
        self.assertEqual(PROBE_ID, result["probe_id"])
        self.assertEqual(PROVIDER_ID, result["provider_id"])
        self.assertEqual(OPERATION, result["operation"])
        self.assertFalse(result["user_file_access"])
        self.assertFalse(result["arbitrary_arguments_allowed"])
        self.assertFalse(result["shell_execution"])
        self.assertFalse(result["network_input_allowed"])
        self.assertFalse(result["persistent_output"])
        self.assertEqual("mpeg4", result["output"]["codec"])
        self.assertEqual(WIDTH, result["output"]["width"])
        self.assertEqual(HEIGHT, result["output"]["height"])
        self.assertEqual(2, len(observed_commands))

        ffmpeg_command = observed_commands[0][0]
        self.assertIn("lavfi", ffmpeg_command)
        self.assertIn(
            f"testsrc2=size={WIDTH}x{HEIGHT}:rate=10",
            ffmpeg_command,
        )
        self.assertTrue(
            all("user.mp4" not in str(arg) for arg in ffmpeg_command)
        )
        self.assertEqual(15.0, observed_commands[0][1])
        self.assertEqual(5.0, observed_commands[1][1])

    def test_failed_transcode_is_fail_closed(self):
        def failing_runner(command, timeout):
            return subprocess.CompletedProcess(
                command,
                1,
                stdout="",
                stderr="synthetic failure",
            )

        with self.assertRaisesRegex(
            RuntimeError,
            "ffmpeg_transcode_failed",
        ):
            run_ffmpeg_transcode_probe(
                "",
                which=lambda name: f"/fake/{name}",
                runner=failing_runner,
            )


if __name__ == "__main__":
    unittest.main()
