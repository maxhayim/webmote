"""Adding a TV: found on the network (or typed in), then paired with the PIN it shows."""

from __future__ import annotations

from collections.abc import Mapping
import ipaddress
from typing import TYPE_CHECKING, Any

import voluptuous as vol

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.const import CONF_HOST, CONF_PIN, CONF_PORT
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import WebmoteClient, WebmoteConnectionError, WebmoteError, WebmotePinError
from .const import CONF_KEY, DEFAULT_PORT, DOMAIN

if TYPE_CHECKING:
    from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo


class WebmoteConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    def __init__(self) -> None:
        self._host: str | None = None
        self._port: int = DEFAULT_PORT
        self._name: str | None = None

    def _client(self, key: str | None = None) -> WebmoteClient:
        return WebmoteClient(async_get_clientsession(self.hass), self._host, self._port, key)

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            host = user_input[CONF_HOST].strip()
            try:
                ipaddress.ip_address(host)
            except ValueError:
                errors[CONF_HOST] = "not_an_address"
            else:
                self._host, self._port = host, user_input[CONF_PORT]
                return await self._show_pin_then_pair()
        return self.async_show_form(
            step_id="user",
            data_schema=self.add_suggested_values_to_schema(
                vol.Schema({vol.Required(CONF_HOST): str, vol.Required(CONF_PORT, default=DEFAULT_PORT): int}),
                user_input,
            ),
            errors=errors,
        )

    async def async_step_zeroconf(self, discovery_info: ZeroconfServiceInfo) -> ConfigFlowResult:
        device_id = discovery_info.properties.get("id")
        if not device_id or discovery_info.ip_address.version != 4:
            return self.async_abort(reason="not_supported")
        self._host, self._port = str(discovery_info.ip_address), discovery_info.port or DEFAULT_PORT
        self._name = discovery_info.name.removesuffix("." + discovery_info.type)
        await self.async_set_unique_id(device_id)
        # A TV already added that moved to a new address: follow it.
        self._abort_if_unique_id_configured(updates={CONF_HOST: self._host, CONF_PORT: self._port})
        self.context["title_placeholders"] = {"name": self._name}
        return await self.async_step_zeroconf_confirm()

    async def async_step_zeroconf_confirm(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if user_input is not None:
            return await self._show_pin_then_pair()
        return self.async_show_form(step_id="zeroconf_confirm", description_placeholders={"name": self._name})

    async def _show_pin_then_pair(self) -> ConfigFlowResult:
        try:
            await self._client().show_pin()
        except WebmoteConnectionError:
            return self.async_show_form(
                step_id="user",
                data_schema=vol.Schema(
                    {vol.Required(CONF_HOST, default=self._host): str, vol.Required(CONF_PORT, default=self._port): int}
                ),
                errors={"base": "cannot_connect"},
            )
        except WebmoteError:
            pass  # An older Webmote without the PIN card: the PIN is on Webmote's own screen.
        return await self.async_step_pair()

    async def async_step_pair(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        placeholders = {"name": self._name or self._host or "the TV", "wait": "0"}
        if user_input is not None:
            client = self._client()
            try:
                key = await client.pair(user_input[CONF_PIN])
                status = await client.status()
            except WebmotePinError as e:
                errors["base"] = "too_many_tries" if e.wait else "invalid_pin"
                placeholders["wait"] = str(e.wait)
            except WebmoteConnectionError:
                errors["base"] = "cannot_connect"
            except WebmoteError:
                errors["base"] = "unknown"
            else:
                if not status.id:
                    return self.async_abort(reason="update_webmote")
                data = {CONF_HOST: self._host, CONF_PORT: self._port, CONF_KEY: key}
                if self.source == "reauth":
                    entry = self._get_reauth_entry()
                    if status.id != entry.unique_id:
                        return self.async_abort(reason="wrong_device")
                    return self.async_update_reload_and_abort(entry, data_updates=data)
                await self.async_set_unique_id(status.id, raise_on_progress=False)
                self._abort_if_unique_id_configured(updates=data)
                return self.async_create_entry(title=status.name, data=data)
        return self.async_show_form(
            step_id="pair",
            data_schema=vol.Schema({vol.Required(CONF_PIN): str}),
            errors=errors,
            description_placeholders=placeholders,
        )

    async def async_step_reauth(self, entry_data: Mapping[str, Any]) -> ConfigFlowResult:
        self._host, self._port = entry_data[CONF_HOST], entry_data[CONF_PORT]
        self._name = self._get_reauth_entry().title
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if user_input is not None:
            return await self._show_pin_then_pair()
        return self.async_show_form(step_id="reauth_confirm", description_placeholders={"name": self._name})
