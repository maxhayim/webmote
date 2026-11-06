"""The TV as a remote: any Webmote button by name, the apps as activities, and typing."""

from __future__ import annotations

import asyncio
from collections.abc import Iterable
from typing import Any

from homeassistant.components.remote import (
    ATTR_DELAY_SECS,
    ATTR_NUM_REPEATS,
    DEFAULT_DELAY_SECS,
    RemoteEntity,
    RemoteEntityFeature,
)
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import KEYS
from .coordinator import WebmoteConfigEntry
from .entity import WebmoteEntity

TEXT = "text:"
SEARCH = "search:"


async def async_setup_entry(
    hass: HomeAssistant, entry: WebmoteConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    async_add_entities([WebmoteRemote(entry.runtime_data)])


class WebmoteRemote(WebmoteEntity, RemoteEntity):
    _attr_supported_features = RemoteEntityFeature.ACTIVITY

    @property
    def current_activity(self) -> str | None:
        return self.status.app or None if self.is_on else None

    @property
    def activity_list(self) -> list[str]:
        return [a.label for a in self.coordinator.data.apps] if self.coordinator.data else []

    async def async_turn_on(self, activity: str | None = None, **kwargs: Any) -> None:
        # Webmote can't wake a sleeping TV; with an activity, it opens that app.
        if activity:
            pkg = self.app_named(activity)
            if pkg is None:
                raise ServiceValidationError(f"{self.coordinator.config_entry.title} has no app called {activity}")
            await self.call(f"opening {activity}", self.coordinator.client.launch(pkg))
        elif not self.is_on:
            raise ServiceValidationError("Webmote can't turn a TV on; use the TV's own remote or HDMI-CEC")

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self.call("turning off", self.coordinator.client.press("off"))

    async def async_send_command(self, command: Iterable[str], **kwargs: Any) -> None:
        """Buttons by name (see KEYS); ``text:words`` types into the focused text box; ``search:words`` also presses Enter."""
        commands = list(command)
        for c in commands:
            if c not in KEYS and not c.startswith((TEXT, SEARCH)):
                raise ServiceValidationError(f"Unknown command {c!r}; use one of {', '.join(KEYS)}, text:… or search:…")
        repeats = kwargs.get(ATTR_NUM_REPEATS, 1)
        delay = kwargs.get(ATTR_DELAY_SECS, DEFAULT_DELAY_SECS)
        client = self.coordinator.client
        first = True
        for _ in range(repeats):
            for c in commands:
                if not first:
                    await asyncio.sleep(delay)
                first = False
                if c.startswith(TEXT):
                    await self.call("typing", client.text(c[len(TEXT):]))
                elif c.startswith(SEARCH):
                    await self.call("searching", client.text(c[len(SEARCH):], enter=True))
                else:
                    await self.call(c, client.press(c))
