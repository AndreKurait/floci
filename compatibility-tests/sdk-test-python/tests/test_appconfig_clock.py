"""The shared clock fixture fails fast and changes time without sleeping."""
from datetime import datetime, timezone

import pytest

from appconfig_clock import AppConfigClock


@pytest.fixture(autouse=True)
def isolated_clock_environment(monkeypatch):
    for name in ("FLOCI_TEST_APPCONFIG_CLOCK_FILE", "FLOCI_TEST_REQUIRE_APPCONFIG_CLOCK", "PYTEST_XDIST_WORKER"):
        monkeypatch.delenv(name, raising=False)


def manual_clock(tmp_path, monkeypatch):
    path = tmp_path / "instant"
    path.write_text("2026-01-01T00:00:00Z")
    monkeypatch.setenv("FLOCI_TEST_APPCONFIG_CLOCK_FILE", str(path))
    return AppConfigClock()


def test_wall_time_fallback_uses_the_requested_wait(monkeypatch):
    waits = []
    monkeypatch.setattr("appconfig_clock.time.sleep", waits.append)
    clock = AppConfigClock()
    assert clock.file is None
    clock.advance(3)
    assert waits == [3]


def test_ci_cannot_silently_fall_back_to_wall_time(monkeypatch):
    monkeypatch.setenv("FLOCI_TEST_REQUIRE_APPCONFIG_CLOCK", "true")
    with pytest.raises(pytest.fail.Exception, match="CI requires"):
        AppConfigClock()


def test_manual_advances_replace_a_readable_timestamp(tmp_path, monkeypatch):
    clock = manual_clock(tmp_path, monkeypatch)
    with clock.exclusive():
        start = clock.monotonic()
        clock.advance(59)
        clock.advance(30)
        assert clock.monotonic() == start + 89
        assert datetime.fromisoformat(clock.file.read_text()).tzinfo == timezone.utc
        assert clock.file.stat().st_mode & 0o777 == 0o644
        clock.assert_started({"StartedAt": datetime.fromtimestamp(start + 89, timezone.utc)})
    assert sorted(path.name for path in tmp_path.iterdir()) == ["instant", "instant.lock"]


def test_two_clients_cannot_advance_the_same_clock_concurrently(tmp_path, monkeypatch):
    first = manual_clock(tmp_path, monkeypatch)
    second = AppConfigClock()
    with first.exclusive():
        with pytest.raises(pytest.fail.Exception, match="Another test process"):
            with second.exclusive():
                pytest.fail("A second client acquired the same clock")
    with second.exclusive():
        second.advance(1)


def test_manual_clock_rejects_xdist_workers(tmp_path, monkeypatch):
    clock = manual_clock(tmp_path, monkeypatch)
    monkeypatch.setenv("PYTEST_XDIST_WORKER", "gw0")
    with pytest.raises(pytest.fail.Exception, match="without xdist"):
        with clock.exclusive():
            pytest.fail("An xdist worker acquired a shared clock")


def test_naive_timestamp_fails_before_requests_are_sent(tmp_path, monkeypatch):
    clock = manual_clock(tmp_path, monkeypatch)
    clock.file.write_text("2026-01-01T00:00:00")
    with pytest.raises(pytest.fail.Exception, match="UTC offset"):
        with clock.exclusive():
            pytest.fail("A timestamp without an offset was accepted")
