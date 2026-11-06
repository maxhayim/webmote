"""What every Webmote entity shares: its device."""

from __future__ import annotations

from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .api import Status, WebmoteError
from .const import DOMAIN
from .coordinator import WebmoteCoordinator


class WebmoteEntity(CoordinatorEntity[WebmoteCoordinator]):
    _attr_has_entity_name = True
    _attr_name = None

    def __init__(self, coordinator: WebmoteCoordinator) -> None:
        super().__init__(coordinator)
        entry = coordinator.config_entry
        self._attr_unique_id = entry.unique_id
        last = coordinator.last_status
        kind = last.kind if last else "tv"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.unique_id)},
            name=entry.title,
            manufacturer="Webmote",
            model="Android TV" if kind == "tv" else "Android head unit or tablet",
            sw_version=last.version if last else None,
            configuration_url=coordinator.client.url,
        )

    @property
    def status(self) -> Status | None:
        return self.coordinator.data.status if self.coordinator.data else None

    @property
    def is_on(self) -> bool:
        s = self.status
        return s is not None and s.screen

    def app_named(self, name: str) -> str | None:
        """The package of the app with this label (or package name), if the TV has it."""
        for a in self.coordinator.data.apps if self.coordinator.data else []:
            if name in (a.label, a.pkg):
                return a.pkg
        return None

    async def call(self, what: str, coro) -> None:
        """Runs one call to the TV, then refreshes soon; the TV saying no becomes an error in the UI."""
        try:
            ok = await coro
        except WebmoteError as e:
            raise HomeAssistantError(f"{self.coordinator.config_entry.title}: {what} failed ({e})") from e
        if ok is False:
            raise HomeAssistantError(f"{self.coordinator.config_entry.title} refused {what}")
        await self.coordinator.async_request_refresh()
