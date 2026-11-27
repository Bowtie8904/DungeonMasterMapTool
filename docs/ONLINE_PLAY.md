# Online Play

Online Play lets the DM host a browser session for players. It is separate from DM Link, which lets another DM control the desktop app over a LAN.

1. In the desktop app, open **Online Play** and start a session.
2. Create player accounts and share the session address with your group. Use a password for every account that can be reached by other people; passwordless accounts can be claimed by anyone who can reach the session.
3. Stop the session when the game is over. The server does not start until you explicitly start it.

Players on the same local network can connect using the address shown by the app. For players outside the network, use a trusted VPN or a secure tunnel to the configured Online Play port. For example, Cloudflare Tunnel can forward to `http://localhost:<port>`; the port is set by `online.port`. Do not expose an unprotected session or forward the port directly from your router.

Online Play is an optional browser interface, not video streaming. Players connect to the host's session while the desktop app remains in control of the game. See [settings](SETTINGS.md#online-play) for server and player preferences, or [DM Link](DM_LINK.md) for remote desktop control.
