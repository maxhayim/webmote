"""A stand-in TV running Webmote, served on 127.0.0.1, for the Home Assistant integration's tests."""

from __future__ import annotations

from pathlib import Path
import sys

from aiohttp import web
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

PIN = "123456"
KEY = "a" * 48


class FakeTv:
    """Answers like Webmote 0.4.0 (docs/api.md), and records what it was asked."""

    def __init__(self) -> None:
        self.status = {
            "tv": "Living Room TV",
            "id": "0123456789abcdef",
            "kind": "tv",
            "version": "0.4.0",
            "screen": True,
            "app": "YouTube",
            "pkg": "com.google.android.youtube.tv",
            "volume": 50,
            "max": 100,
            "muted": False,
        }
        self.apps = [
            {"label": "Kodi", "pkg": "org.xbmc.kodi"},
            {"label": "YouTube", "pkg": "com.google.android.youtube.tv"},
        ]
        self.keys: list[str] = []
        self.texts: list[dict] = []
        self.launched: list[str] = []
        self.pin_shown = 0
        self.key_valid = True

    def app(self) -> web.Application:
        app = web.Application()
        app.router.add_post("/api/pair/show", self.show)
        app.router.add_post("/api/pair", self.pair)
        app.router.add_get("/api/status", self.guard(self.get_status))
        app.router.add_get("/api/apps", self.guard(self.get_apps))
        app.router.add_post("/api/key", self.guard(self.key))
        app.router.add_post("/api/volume", self.guard(self.volume))
        app.router.add_post("/api/launch", self.guard(self.launch))
        app.router.add_post("/api/text", self.guard(self.text))
        return app

    def guard(self, handler):
        async def check(request: web.Request) -> web.Response:
            if not self.key_valid or request.headers.get("X-Webmote-Key") != KEY:
                return web.json_response({"error": "Pair this device again."}, status=401)
            return await handler(request)

        return check

    async def show(self, request: web.Request) -> web.Response:
        self.pin_shown += 1
        return web.json_response({"ok": True})

    async def pair(self, request: web.Request) -> web.Response:
        if (await request.json()).get("pin") != PIN:
            return web.json_response({"error": "That PIN isn't right."}, status=403)
        self.key_valid = True
        return web.json_response({"key": KEY})

    async def get_status(self, request: web.Request) -> web.Response:
        return web.json_response(self.status)

    async def get_apps(self, request: web.Request) -> web.Response:
        return web.json_response(self.apps)

    async def key(self, request: web.Request) -> web.Response:
        k = (await request.json())["key"]
        self.keys.append(k)
        if k == "mute":
            self.status["muted"] = not self.status["muted"]
        if k == "off":
            self.status["screen"] = False
        return web.json_response({"ok": True})

    async def volume(self, request: web.Request) -> web.Response:
        self.status["volume"] = (await request.json())["level"]
        return web.json_response({"ok": True})

    async def launch(self, request: web.Request) -> web.Response:
        pkg = (await request.json())["pkg"]
        self.launched.append(pkg)
        return web.json_response({"ok": pkg in [a["pkg"] for a in self.apps]})

    async def text(self, request: web.Request) -> web.Response:
        self.texts.append(await request.json())
        return web.json_response({"ok": True})


@pytest.fixture(autouse=True)
def auto_enable_custom_integrations(enable_custom_integrations):
    yield


@pytest.fixture
async def tv(socket_enabled, aiohttp_server):
    fake = FakeTv()
    server = await aiohttp_server(fake.app(), host="127.0.0.1")
    fake.port = server.port
    return fake
