"""Webmote on the TV: its version, the latest release, and Install, which has the TV update itself from GitHub."""

from __future__ import annotations

from typing import Any

from awesomeversion import AwesomeVersion

from homeassistant.components.update import UpdateEntity, UpdateEntityFeature
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import RELEASES
from .coordinator import WebmoteConfigEntry, WebmoteCoordinator
from .entity import WebmoteEntity

# What the TV says while it's at it.
BUSY = ("Checking", "Downloading", "Installing")


async def async_setup_entry(
    hass: HomeAssistant, entry: WebmoteConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    async_add_entities([WebmoteUpdate(entry.runtime_data)])


class WebmoteUpdate(WebmoteEntity, UpdateEntity):
    _attr_translation_key = "app"
    _attr_supported_features = UpdateEntityFeature.INSTALL | UpdateEntityFeature.PROGRESS

    def __init__(self, coordinator: WebmoteCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{coordinator.config_entry.unique_id}_update"
        self._last = coordinator.last_status

    def _handle_coordinator_update(self) -> None:
        # Keeps the last version seen, so the entity doesn't go blank while the TV sleeps.
        if self.status is not None:
            self._last = self.status
        super()._handle_coordinator_update()

    @property
    def available(self) -> bool:
        return self._last is not None and self._last.version is not None

    @property
    def installed_version(self) -> str | None:
        return self._last.version if self._last else None

    @property
    def latest_version(self) -> str | None:
        s = self._last
        if s is None or s.version is None:
            return None
        # Before its first check (or before 0.5.0, which doesn't check) the TV knows of nothing newer.
        if not s.latest or AwesomeVersion(s.latest) <= AwesomeVersion(s.version):
            return s.version
        return s.latest

    @property
    def release_url(self) -> str | None:
        latest = self.latest_version
        return f"{RELEASES}/tag/v{latest}" if latest else RELEASES

    @property
    def in_progress(self) -> bool:
        s = self.status
        return s is not None and s.update.startswith(BUSY)

    async def async_install(self, version: str | None, backup: bool, **kwargs: Any) -> None:
        await self.call("updating Webmote", self.coordinator.client.update())
