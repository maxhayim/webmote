"""Keeps up with one TV: its status every few seconds, its apps now and then."""

from __future__ import annotations

from dataclasses import dataclass, field
import logging

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator
from homeassistant.util import dt as dt_util

from .api import App, Status, WebmoteAuthError, WebmoteClient, WebmoteError
from .const import APPS_INTERVAL, DOMAIN, SCAN_INTERVAL

_LOGGER = logging.getLogger(__name__)


@dataclass
class WebmoteData:
    """The TV's state. ``status`` is None while it can't be reached (asleep, or off at the wall)."""

    status: Status | None = None
    apps: list[App] = field(default_factory=list)


type WebmoteConfigEntry = ConfigEntry[WebmoteCoordinator]


class WebmoteCoordinator(DataUpdateCoordinator[WebmoteData]):
    """Polls one TV. A TV that can't be reached reads as off rather than unavailable: TVs go quiet when asleep."""

    config_entry: WebmoteConfigEntry

    def __init__(self, hass: HomeAssistant, entry: WebmoteConfigEntry, client: WebmoteClient) -> None:
        super().__init__(hass, _LOGGER, config_entry=entry, name=DOMAIN, update_interval=SCAN_INTERVAL)
        self.client = client
        self._apps_at = None
        self.last_status: Status | None = None

    async def _async_update_data(self) -> WebmoteData:
        apps = self.data.apps if self.data else []
        try:
            status = await self.client.status()
            now = dt_util.utcnow()
            if not apps or self._apps_at is None or now - self._apps_at > APPS_INTERVAL:
                apps = await self.client.apps()
                self._apps_at = now
        except WebmoteAuthError as e:
            raise ConfigEntryAuthFailed("Webmote dropped this key: pair again with the PIN on the TV") from e
        except WebmoteError as e:
            _LOGGER.debug("%s can't be reached: %s", self.client.url, e)
            return WebmoteData(None, apps)
        self.last_status = status
        return WebmoteData(status, apps)
