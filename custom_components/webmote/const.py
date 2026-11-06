"""Constants for Webmote."""

from datetime import timedelta

DOMAIN = "webmote"
DEFAULT_PORT = 8080
CONF_KEY = "key"

SCAN_INTERVAL = timedelta(seconds=10)
APPS_INTERVAL = timedelta(minutes=10)

# The remote's buttons, as the TV's API names them.
KEYS = [
    "up", "down", "left", "right", "ok",
    "back", "home", "recents", "settings",
    "vol_up", "vol_down", "mute",
    "play_pause", "next", "previous",
    "off",
]
