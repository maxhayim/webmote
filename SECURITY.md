# Security Policy – Webmote

Webmote turns an Android TV into a small web server on the home network, so a phone or computer can work the TV like
its remote. Anything that can press a TV's keys can open apps, change settings and type into them, so the server
is locked down by default and this policy is taken seriously.

---

## Reporting a Vulnerability

If you discover a security vulnerability:

- Do NOT open a public issue.
- Use GitHub's [Private Vulnerability Reporting](../../security/advisories/new) for this repo.
- Include:
  - A clear description of the issue
  - Steps to reproduce
  - Affected version(s)
  - Any logs or screenshots (sanitized: no IP addresses, network names or pairing keys)

Please allow reasonable time for investigation and patching before public disclosure.

Responsible disclosure is required.

---

## What Webmote Does to Stay Safe

- **Home network only.** Connections from anything but a private address (192.168.x.x, 10.x.x.x, 172.16–31.x.x,
  link-local, IPv6 unique-local, and 100.64.0.0/10 for VPNs such as Tailscale) are refused before a request is read.
- **Pairing.** Every control call needs a key that the TV hands out only for the 6-digit PIN shown on its screen.
  Wrong PINs lock pairing: after 5 tries, 30 seconds, doubling up to 5 minutes. **New PIN** on the TV signs every
  paired device out. Up to 20 devices stay paired; the oldest drops off.
- **Keys are stored hashed.** The TV keeps only SHA-256 hashes of the keys, and compares them in constant time.
- **No cross-site access.** The key travels in a custom header, which a page on another site can't send without the
  browser asking first, and Webmote never says yes (no CORS headers). Requests whose `Host` is a name rather than an
  address are refused, which stops DNS-rebinding attacks.
- **Hardened responses.** A strict Content-Security-Policy, `X-Content-Type-Options: nosniff`, no referrers, and
  `frame-ancestors 'none'` against clickjacking.
- **Small requests only.** Headers are capped at 8 KB and bodies at 16 KB, each connection has a 10-second
  timeout, and a small fixed thread pool serves them.
- **Least privilege.** The accessibility service listens only for which app comes to the front, and reads the
  screen only when the page types, to find the focused text box. It doesn't filter keys, perform gestures or log
  anything. The app asks for no permissions beyond the network and its accessibility service.

---

## Network Exposure

Webmote speaks plain HTTP. On a home network that's the usual trade-off for a local device (a TV can't get a
trusted certificate for a private address), but it means:

- **Never forward Webmote's port (8080) to the internet**, and never expose ADB (port 5555) either.
- Anyone who can watch traffic on your network could read a pairing key in transit. On a shared or untrusted
  network (dorms, offices, hotels), don't run Webmote, or press **New PIN** afterwards.
- To use it away from home, use a VPN to your home network (WireGuard, Tailscale) rather than a port forward.
- Guest Wi-Fi with client isolation will (correctly) stop phones on it reaching the TV.

---

## Device Security

- Install Webmote only from this repository's releases, or build it from source. Release APKs are signed with the
  project's own key; Android refuses an update signed by anyone else.
- Remove paired devices you no longer use with **New PIN**.
- Turning the remote service off in Accessibility settings stops the server at once.

---

## Supported Versions

Security updates are applied to:

- The latest tagged release

Older versions may not receive patches.

---

## Secure Development Practices

Webmote development guidelines:

- No hardcoded keys, PINs or credentials
- No debug backdoors, no remote logging, no analytics
- Every new API route requires the pairing key, unless there is a written reason it can't
- Validate and bound all request input; never pass it to a shell
- Keep the accessibility service's reach as small as it is now

Pull Requests touching the server, pairing or the accessibility service will receive additional review.
