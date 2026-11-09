"""The TV as a media player: on/off, volume, play/pause, tracks, and its apps as sources."""

from __future__ import annotations

from homeassistant.components.media_player import (
    MediaPlayerDeviceClass,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
)
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .coordinator import WebmoteConfigEntry
from .entity import WebmoteEntity


async def async_setup_entry(
    hass: HomeAssistant, entry: WebmoteConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    async_add_entities([WebmoteMediaPlayer(entry.runtime_data)])


class WebmoteMediaPlayer(WebmoteEntity, MediaPlayerEntity):
    _attr_name = None  # the TV's own name
    # Turning on needs Webmote's standby (0.5.0 and newer): a TV asleep without it is off the network.
    _attr_supported_features = (
        MediaPlayerEntityFeature.VOLUME_SET
        | MediaPlayerEntityFeature.VOLUME_STEP
        | MediaPlayerEntityFeature.VOLUME_MUTE
        | MediaPlayerEntityFeature.PLAY
        | MediaPlayerEntityFeature.PAUSE
        | MediaPlayerEntityFeature.NEXT_TRACK
        | MediaPlayerEntityFeature.PREVIOUS_TRACK
        | MediaPlayerEntityFeature.SELECT_SOURCE
        | MediaPlayerEntityFeature.TURN_OFF
        | MediaPlayerEntityFeature.TURN_ON
    )

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator)
        last = coordinator.last_status
        self._attr_device_class = (
            MediaPlayerDeviceClass.TV if last is None or last.kind == "tv" else MediaPlayerDeviceClass.RECEIVER
        )

    @property
    def state(self) -> MediaPlayerState:
        return MediaPlayerState.ON if self.is_on else MediaPlayerState.OFF

    @property
    def volume_level(self) -> float | None:
        s = self.status
        return s.volume / s.max if s and s.max else None

    @property
    def is_volume_muted(self) -> bool | None:
        return self.status.muted if self.status else None

    @property
    def source(self) -> str | None:
        return self.status.app or None if self.is_on else None

    @property
    def source_list(self) -> list[str]:
        return [a.label for a in self.coordinator.data.apps] if self.coordinator.data else []

    @property
    def app_id(self) -> str | None:
        return self.status.pkg or None if self.is_on else None

    @property
    def app_name(self) -> str | None:
        return self.source

    async def async_turn_on(self) -> None:
        await self.turn_on()

    async def async_turn_off(self) -> None:
        await self.call("turning off", self.coordinator.client.press("off"))

    async def async_volume_up(self) -> None:
        await self.call("volume up", self.coordinator.client.press("vol_up"))

    async def async_volume_down(self) -> None:
        await self.call("volume down", self.coordinator.client.press("vol_down"))

    async def async_set_volume_level(self, volume: float) -> None:
        s = self.status
        if s is None or not s.max:
            raise ServiceValidationError(f"{self.coordinator.config_entry.title} is off")
        await self.call("setting the volume", self.coordinator.client.set_volume(round(volume * s.max)))

    async def async_mute_volume(self, mute: bool) -> None:
        # The TV only toggles mute, so press it only when it would change something.
        if self.status is None or self.status.muted != mute:
            await self.call("mute", self.coordinator.client.press("mute"))

    async def async_media_play_pause(self) -> None:
        await self.call("play/pause", self.coordinator.client.press("play_pause"))

    # Android has one play/pause key, and Webmote can't tell whether something is playing.
    async def async_media_play(self) -> None:
        await self.async_media_play_pause()

    async def async_media_pause(self) -> None:
        await self.async_media_play_pause()

    async def async_media_next_track(self) -> None:
        await self.call("next track", self.coordinator.client.press("next"))

    async def async_media_previous_track(self) -> None:
        await self.call("previous track", self.coordinator.client.press("previous"))

    async def async_select_source(self, source: str) -> None:
        pkg = self.app_named(source)
        if pkg is None:
            raise ServiceValidationError(f"{self.coordinator.config_entry.title} has no app called {source}")
        await self.call(f"opening {source}", self.coordinator.client.launch(pkg))
