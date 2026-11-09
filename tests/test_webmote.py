"""The Home Assistant integration against a stand-in TV: adding it, then using it."""

from __future__ import annotations

from ipaddress import ip_address

from homeassistant import config_entries
from homeassistant.const import CONF_HOST, CONF_PIN, CONF_PORT
from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType
import pytest

try:
    from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo
except ImportError:  # Home Assistant before 2025.2
    from homeassistant.components.zeroconf import ZeroconfServiceInfo
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.webmote.const import CONF_KEY, DOMAIN

from .conftest import KEY, PIN

MP = "media_player.living_room_tv"
REMOTE = "remote.living_room_tv"


def discovery(port: int, host: str = "127.0.0.1") -> ZeroconfServiceInfo:
    return ZeroconfServiceInfo(
        ip_address=ip_address(host),
        ip_addresses=[ip_address(host)],
        hostname="android.local.",
        name="Living Room TV._webmote._tcp.local.",
        port=port,
        type="_webmote._tcp.local.",
        properties={"id": "0123456789abcdef", "kind": "tv", "version": "0.4.0"},
    )


async def refresh(hass: HomeAssistant) -> None:
    """The next poll, now."""
    for entry in hass.config_entries.async_entries(DOMAIN):
        await entry.runtime_data.async_refresh()
    await hass.async_block_till_done()


async def add(hass: HomeAssistant, tv) -> MockConfigEntry:
    entry = MockConfigEntry(
        domain=DOMAIN,
        title="Living Room TV",
        unique_id=tv.status["id"],
        data={CONF_HOST: "127.0.0.1", CONF_PORT: tv.port, CONF_KEY: KEY},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


async def test_add_by_address(hass: HomeAssistant, tv) -> None:
    r = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    assert r["step_id"] == "user"
    r = await hass.config_entries.flow.async_configure(r["flow_id"], {CONF_HOST: "living-room.local", CONF_PORT: tv.port})
    assert r["errors"] == {CONF_HOST: "not_an_address"}
    r = await hass.config_entries.flow.async_configure(r["flow_id"], {CONF_HOST: "127.0.0.1", CONF_PORT: tv.port})
    assert r["step_id"] == "pair"
    assert tv.pin_shown == 1
    r = await hass.config_entries.flow.async_configure(r["flow_id"], {CONF_PIN: "000000"})
    assert r["errors"] == {"base": "invalid_pin"}
    r = await hass.config_entries.flow.async_configure(r["flow_id"], {CONF_PIN: PIN})
    assert r["type"] is FlowResultType.CREATE_ENTRY
    assert r["title"] == "Living Room TV"
    assert r["data"] == {CONF_HOST: "127.0.0.1", CONF_PORT: tv.port, CONF_KEY: KEY}
    assert r["result"].unique_id == "0123456789abcdef"


async def test_unreachable_address(hass: HomeAssistant, socket_enabled, unused_tcp_port) -> None:
    r = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    r = await hass.config_entries.flow.async_configure(r["flow_id"], {CONF_HOST: "127.0.0.1", CONF_PORT: unused_tcp_port})
    assert r["errors"] == {"base": "cannot_connect"}


async def test_found_on_the_network(hass: HomeAssistant, tv) -> None:
    r = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=discovery(tv.port)
    )
    assert r["step_id"] == "zeroconf_confirm"
    assert r["description_placeholders"] == {"name": "Living Room TV"}
    r = await hass.config_entries.flow.async_configure(r["flow_id"], {})
    assert r["step_id"] == "pair"
    r = await hass.config_entries.flow.async_configure(r["flow_id"], {CONF_PIN: PIN})
    assert r["type"] is FlowResultType.CREATE_ENTRY
    await hass.async_block_till_done()
    assert hass.states.get(MP).state == "on"


async def test_follows_a_new_address(hass: HomeAssistant, tv) -> None:
    entry = MockConfigEntry(
        domain=DOMAIN, unique_id="0123456789abcdef", data={CONF_HOST: "10.0.0.9", CONF_PORT: 8080, CONF_KEY: KEY}
    )
    entry.add_to_hass(hass)
    r = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=discovery(tv.port)
    )
    assert r["type"] is FlowResultType.ABORT and r["reason"] == "already_configured"
    assert entry.data[CONF_HOST] == "127.0.0.1" and entry.data[CONF_PORT] == tv.port


async def test_old_webmote_is_not_offered(hass: HomeAssistant, tv) -> None:
    d = discovery(tv.port)
    d.properties.pop("id")
    r = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=d)
    assert r["reason"] == "not_supported"


async def test_media_player(hass: HomeAssistant, tv) -> None:
    await add(hass, tv)
    s = hass.states.get(MP)
    assert s.state == "on"
    assert s.attributes["device_class"] == "tv"
    assert s.attributes["volume_level"] == 0.5
    assert s.attributes["is_volume_muted"] is False
    assert s.attributes["source"] == "YouTube"
    assert s.attributes["source_list"] == ["Kodi", "YouTube"]
    assert s.attributes["app_id"] == "com.google.android.youtube.tv"

    await hass.services.async_call("media_player", "volume_set", {"entity_id": MP, "volume_level": 0.3}, blocking=True)
    assert tv.status["volume"] == 30
    await hass.services.async_call("media_player", "volume_up", {"entity_id": MP}, blocking=True)
    await hass.services.async_call("media_player", "media_play_pause", {"entity_id": MP}, blocking=True)
    await hass.services.async_call("media_player", "media_next_track", {"entity_id": MP}, blocking=True)
    await hass.services.async_call("media_player", "volume_mute", {"entity_id": MP, "is_volume_muted": True}, blocking=True)
    assert tv.keys == ["vol_up", "play_pause", "next", "mute"]
    await hass.services.async_call("media_player", "select_source", {"entity_id": MP, "source": "Kodi"}, blocking=True)
    assert tv.launched == ["org.xbmc.kodi"]
    with pytest.raises(Exception, match="no app called Netflix"):
        await hass.services.async_call("media_player", "select_source", {"entity_id": MP, "source": "Netflix"}, blocking=True)

    await hass.services.async_call("media_player", "turn_off", {"entity_id": MP}, blocking=True)
    await refresh(hass)
    assert hass.states.get(MP).state == "off"
    # In standby the TV is still on the network, so it can be turned back on.
    await hass.services.async_call("media_player", "turn_on", {"entity_id": MP}, blocking=True)
    await refresh(hass)
    assert hass.states.get(MP).state == "on"
    assert tv.keys[-2:] == ["off", "on"]


async def test_remote(hass: HomeAssistant, tv) -> None:
    await add(hass, tv)
    s = hass.states.get(REMOTE)
    assert s.state == "on"
    assert s.attributes["current_activity"] == "YouTube"
    assert s.attributes["activity_list"] == ["Kodi", "YouTube"]
    await hass.services.async_call(
        "remote",
        "send_command",
        {"entity_id": REMOTE, "command": ["down", "ok", "text:cartoons", "search:cats"], "delay_secs": 0},
        blocking=True,
    )
    assert tv.keys == ["down", "ok"]
    assert tv.texts == [{"text": "cartoons", "enter": False}, {"text": "cats", "enter": True}]
    await hass.services.async_call(
        "remote", "send_command", {"entity_id": REMOTE, "command": "right", "num_repeats": 3, "delay_secs": 0}, blocking=True
    )
    assert tv.keys[2:] == ["right"] * 3
    with pytest.raises(Exception, match="Unknown command"):
        await hass.services.async_call("remote", "send_command", {"entity_id": REMOTE, "command": "jump"}, blocking=True)
    await hass.services.async_call("remote", "turn_on", {"entity_id": REMOTE, "activity": "Kodi"}, blocking=True)
    assert tv.launched == ["org.xbmc.kodi"]


async def test_tv_asleep_reads_as_off(hass: HomeAssistant, tv) -> None:
    entry = await add(hass, tv)
    entry.runtime_data.client.port = 1  # nothing answers there
    await refresh(hass)
    assert hass.states.get(MP).state == "off"
    assert hass.states.get(REMOTE).state == "off"
    with pytest.raises(Exception, match="can't be reached to turn it on"):
        await hass.services.async_call("media_player", "turn_on", {"entity_id": MP}, blocking=True)
    assert hass.states.get("update.living_room_tv_webmote_app").state == "off"  # still knows its version
    entry.runtime_data.client.port = tv.port
    await refresh(hass)
    assert hass.states.get(MP).state == "on"


async def test_new_pin_asks_to_pair_again(hass: HomeAssistant, tv) -> None:
    entry = await add(hass, tv)
    tv.key_valid = False
    await refresh(hass)
    flows = hass.config_entries.flow.async_progress()
    assert len(flows) == 1 and flows[0]["context"]["source"] == "reauth"
    r = await hass.config_entries.flow.async_configure(flows[0]["flow_id"], {})
    assert r["step_id"] == "pair"
    r = await hass.config_entries.flow.async_configure(r["flow_id"], {CONF_PIN: PIN})
    assert r["type"] is FlowResultType.ABORT and r["reason"] == "reauth_successful"
    assert entry.data[CONF_KEY] == KEY


async def test_buttons(hass: HomeAssistant, tv) -> None:
    await add(hass, tv)
    for name in ("up", "down", "left", "right", "ok", "back", "home", "recent_apps", "settings"):
        assert hass.states.get(f"button.living_room_tv_{name}") is not None, name
    await hass.services.async_call("button", "press", {"entity_id": "button.living_room_tv_ok"}, blocking=True)
    await hass.services.async_call("button", "press", {"entity_id": "button.living_room_tv_recent_apps"}, blocking=True)
    assert tv.keys == ["ok", "recents"]


async def test_head_unit_has_no_d_pad(hass: HomeAssistant, tv) -> None:
    tv.status["kind"] = "touch"
    await add(hass, tv)
    assert hass.states.get("button.living_room_tv_up") is None
    assert hass.states.get("button.living_room_tv_home") is not None


async def test_update(hass: HomeAssistant, tv) -> None:
    tv.status["latest"] = "0.6.0"
    tv.status["update"] = "Version 0.6.0 is available"
    await add(hass, tv)
    u = "update.living_room_tv_webmote_app"
    s = hass.states.get(u)
    assert s.state == "on"
    assert s.attributes["installed_version"] == "0.5.0"
    assert s.attributes["latest_version"] == "0.6.0"
    assert s.attributes["release_url"].endswith("/releases/tag/v0.6.0")
    await hass.services.async_call("update", "install", {"entity_id": u}, blocking=True)
    assert tv.updates == 1
    await refresh(hass)
    assert hass.states.get(u).attributes["in_progress"] is True


async def test_old_webmote_knows_of_no_update(hass: HomeAssistant, tv) -> None:
    """Webmote before 0.5.0 doesn't check GitHub, so it reads as up to date."""
    for k in ("standby", "latest", "update"):
        tv.status.pop(k)
    tv.status["version"] = "0.4.0"
    await add(hass, tv)
    u = "update.living_room_tv_webmote_app"
    assert hass.states.get(u).state == "off"
    assert hass.states.get(u).attributes["installed_version"] == "0.4.0"
