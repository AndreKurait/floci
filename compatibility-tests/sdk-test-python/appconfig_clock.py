"""A file-backed clock shared by AppConfig SDK compatibility tests."""

import datetime
import os
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path

import pytest


class AppConfigClock:
    """Advance the qualified AppConfigService clock in the compatibility build."""

    def __init__(self):
        configured = os.environ.get("FLOCI_TEST_APPCONFIG_CLOCK_FILE")
        self.file = Path(configured) if configured else None
        if self.file is None and os.environ.get("FLOCI_TEST_REQUIRE_APPCONFIG_CLOCK") == "true":
            pytest.fail("CI requires FLOCI_TEST_APPCONFIG_CLOCK_FILE and a compat-clock build")

    @contextmanager
    def exclusive(self):
        if self.file is None:
            yield
            return
        if os.environ.get("PYTEST_XDIST_WORKER"):
            pytest.fail("AppConfig manual-clock tests require a dedicated Floci instance without xdist")
        import fcntl

        # Lock a separate inode: replacing the timestamp must not release the lock.
        with self.file.with_name(self.file.name + ".lock").open("a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                pytest.fail("Another test process is using this AppConfig clock")
            try:
                self.monotonic()  # Reject unreadable or invalid timestamps before making requests.
                yield
            finally:
                fcntl.flock(lock, fcntl.LOCK_UN)

    def _instant(self):
        instant = datetime.datetime.fromisoformat(self.file.read_text().strip())
        if instant.tzinfo is None:
            pytest.fail("The AppConfig clock timestamp must include a UTC offset")
        return instant

    def monotonic(self):
        if self.file is None:
            return time.monotonic()
        return self._instant().timestamp()

    def advance(self, seconds):
        assert seconds >= 0
        if self.file is None:
            time.sleep(seconds)
            return
        instant = self._instant()
        with tempfile.NamedTemporaryFile(mode="w", dir=self.file.parent, delete=False) as output:
            next_file = Path(output.name)
            output.write((instant + datetime.timedelta(seconds=seconds)).isoformat())
        try:
            next_file.chmod(0o644)  # The Floci container may run under a different uid.
            next_file.replace(self.file)
        finally:
            next_file.unlink(missing_ok=True)

    def assert_started(self, deployment):
        if self.file is not None:
            assert deployment["StartedAt"].timestamp() == self.monotonic(), (
                "Floci must use the compat-clock build and the same clock directory as the SDK client")
