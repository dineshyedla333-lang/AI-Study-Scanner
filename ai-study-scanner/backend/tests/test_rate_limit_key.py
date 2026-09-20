"""The rate-limit key must prefer the device header over the client IP.

Indian carriers NAT many subscribers behind one address, so IP-only keying made
real users throttle each other once traffic grew.
"""
from main import _rate_limit_key


class _Req:
    def __init__(self, headers=None, host="203.0.113.7"):
        self.headers = headers or {}
        self.client = type("C", (), {"host": host})()


def test_uses_device_header_when_present():
    assert _rate_limit_key(_Req({"X-Device-Id": "anon_abc"})) == "dev:anon_abc"


def test_two_devices_behind_one_ip_get_separate_keys():
    a = _rate_limit_key(_Req({"X-Device-Id": "anon_a"}))
    b = _rate_limit_key(_Req({"X-Device-Id": "anon_b"}))
    assert a != b


def test_falls_back_to_ip_without_header():
    assert _rate_limit_key(_Req()) == "203.0.113.7"


def test_device_id_is_truncated():
    key = _rate_limit_key(_Req({"X-Device-Id": "x" * 500}))
    assert len(key) == len("dev:") + 64


def test_whitespace_is_stripped():
    assert _rate_limit_key(_Req({"X-Device-Id": "  anon_z  "})) == "dev:anon_z"
