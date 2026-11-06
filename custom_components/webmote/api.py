"""The TV's HTTP API (docs/api.md), for Home Assistant."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any

import aiohttp

TIMEOUT = aiohttp.ClientTimeout(total=10)


class WebmoteError(Exception):
    """The TV said no, or answered something unexpected."""


class WebmoteConnectionError(WebmoteError):
    """The TV can't be reached: off, asleep, or Webmote's service is off."""


class WebmoteAuthError(WebmoteError):
    """The key is wrong or was dropped (New PIN on the TV): pair again."""


class WebmotePinError(WebmoteError):
    """Pairing failed. ``wait`` is how many seconds the TV wants before the next try (0: try again now)."""

    def __init__(self, wait: int = 0) -> None:
        super().__init__("wrong PIN" if not wait else f"too many tries, wait {wait} s")
        self.wait = wait


@dataclass(frozen=True)
class Status:
    """What /api/status says."""

    name: str
    id: str | None
    kind: str
    version: str | None
    screen: bool
    app: str
    pkg: str
    volume: int
    max: int
    muted: bool

    @classmethod
    def from_json(cls, j: dict[str, Any]) -> Status:
        return cls(
            name=str(j.get("tv") or "TV"),
            id=j.get("id"),
            kind=str(j.get("kind") or "tv"),
            version=j.get("version"),
            screen=bool(j.get("screen", True)),
            app=str(j.get("app") or ""),
            pkg=str(j.get("pkg") or ""),
            volume=int(j.get("volume") or 0),
            max=int(j.get("max") or 0),
            muted=bool(j.get("muted")),
        )


@dataclass(frozen=True)
class App:
    """One app the TV can open."""

    label: str
    pkg: str


class WebmoteClient:
    """Talks to one TV. The address must be an IP address: Webmote refuses names in the Host header."""

    def __init__(self, session: aiohttp.ClientSession, host: str, port: int, key: str | None = None) -> None:
        self._session = session
        self.host = host
        self.port = port
        self.key = key

    @property
    def url(self) -> str:
        host = f"[{self.host}]" if ":" in self.host else self.host
        return f"http://{host}:{self.port}"

    async def _call(self, method: str, path: str, body: dict[str, Any] | None = None, auth: bool = True) -> Any:
        headers = {"X-Webmote-Key": self.key} if auth and self.key else {}
        try:
            async with self._session.request(
                method, self.url + path, json=body, headers=headers, timeout=TIMEOUT
            ) as r:
                if r.status == 401:
                    raise WebmoteAuthError
                j = await r.json(content_type=None)
                if path == "/api/pair" and r.status in (403, 429):
                    raise WebmotePinError(int(j.get("wait") or 0) if r.status == 429 else 0)
                if r.status != 200:
                    raise WebmoteError(f"{path}: HTTP {r.status}")
                return j
        except (aiohttp.ClientConnectionError, asyncio.TimeoutError) as e:
            raise WebmoteConnectionError(str(e)) from e
        except (aiohttp.ContentTypeError, ValueError) as e:
            raise WebmoteError(f"{path}: not JSON") from e

    async def show_pin(self) -> None:
        """Shows the PIN in a corner of the TV for a minute."""
        await self._call("POST", "/api/pair/show", {}, auth=False)

    async def pair(self, pin: str) -> str:
        """Pairs with the PIN on the TV; keeps and returns the key."""
        j = await self._call("POST", "/api/pair", {"pin": pin.strip()}, auth=False)
        self.key = str(j["key"])
        return self.key

    async def status(self) -> Status:
        return Status.from_json(await self._call("GET", "/api/status"))

    async def apps(self) -> list[App]:
        return [App(str(a["label"]), str(a["pkg"])) for a in await self._call("GET", "/api/apps")]

    async def _ok(self, path: str, body: dict[str, Any]) -> bool:
        j = await self._call("POST", path, body)
        return bool(j.get("ok"))

    async def press(self, key: str) -> bool:
        return await self._ok("/api/key", {"key": key})

    async def set_volume(self, level: int) -> bool:
        return await self._ok("/api/volume", {"level": level})

    async def launch(self, pkg: str) -> bool:
        return await self._ok("/api/launch", {"pkg": pkg})

    async def text(self, text: str, enter: bool = False) -> bool:
        return await self._ok("/api/text", {"text": text, "enter": enter})
