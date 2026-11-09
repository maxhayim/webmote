"""The remote's buttons, one by one: the D-pad, OK, Back, Home and the rest, for the device page and dashboards."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity, ButtonEntityDescription
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .coordinator import WebmoteConfigEntry, WebmoteCoordinator
from .entity import WebmoteEntity

BUTTONS = [
    ButtonEntityDescription(key="up", translation_key="up", icon="mdi:chevron-up"),
    ButtonEntityDescription(key="down", translation_key="down", icon="mdi:chevron-down"),
    ButtonEntityDescription(key="left", translation_key="left", icon="mdi:chevron-left"),
    ButtonEntityDescription(key="right", translation_key="right", icon="mdi:chevron-right"),
    ButtonEntityDescription(key="ok", translation_key="ok", icon="mdi:circle-medium"),
    ButtonEntityDescription(key="back", translation_key="back", icon="mdi:arrow-left"),
    ButtonEntityDescription(key="home", translation_key="home", icon="mdi:home"),
    ButtonEntityDescription(key="recents", translation_key="recents", icon="mdi:view-carousel-outline"),
    ButtonEntityDescription(key="settings", translation_key="settings", icon="mdi:cog"),
]
# A car head unit or tablet is worked by touch, so it gets no D-pad (as on its page).
TOUCH = {"up", "down", "left", "right", "ok"}


async def async_setup_entry(
    hass: HomeAssistant, entry: WebmoteConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator = entry.runtime_data
    last = coordinator.last_status
    touch = last is not None and last.kind != "tv"
    async_add_entities(
        WebmoteButton(coordinator, d) for d in BUTTONS if not (touch and d.key in TOUCH)
    )


class WebmoteButton(WebmoteEntity, ButtonEntity):
    def __init__(self, coordinator: WebmoteCoordinator, description: ButtonEntityDescription) -> None:
        super().__init__(coordinator)
        self.entity_description = description
        self._attr_unique_id = f"{coordinator.config_entry.unique_id}_{description.key}"

    async def async_press(self) -> None:
        await self.call(self.entity_description.key, self.coordinator.client.press(self.entity_description.key))
