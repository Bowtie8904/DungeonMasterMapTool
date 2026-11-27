# DM Link (LAN remote control)

Open the **DM Link** tab (formerly "Session") in the left sidebar (next to Tokens; hideable via Settings > DM controls tabs) to control a host PC (A) from another PC (B) running the
same remote compatibility version. Both use the normal DM interface, with independent cameras, tools and
selections. Completed edits, shared Undo/Redo, map/token libraries and settings synchronize; there is
no video streaming and unfinished drawing previews stay local. Player windows and all audio output
run only on A. A remains authoritative when edits overlap.

Drag maps, multilevel maps or folders in B's map library onto a folder or the library root
to move them on A. Multiple selected items move together; B's local library is not changed.

Compatibility is defined by `RemoteCompatibility.VERSION`, not an application-content hash or release number. Increment this version when changes to remote messages,
commands, snapshots, settings or asset transfers break compatibility; compatible fixes and unrelated
application changes keep the version. Different application builds and packaging can connect when
this version and the wire protocol match. Mismatches reject before approval and show both versions.
Update and restart both PCs when moving from older releases that used content hashes.

Host map-artwork and operation failures appear on B in a **Remote control error** dialog. Expand
the details to read/select the original stack trace, or use **Copy details** to copy the message and
trace without closing the dialog. Repeated identical failures are deduplicated. Diagnostics are
bounded and sent only after approval; protocol/parser payloads and authentication secrets are not
included. Workspace failures pause edits without blocking library browsing or local import pickers.
Correct the reported artwork problem on A and reopen the map to republish it; failed previews can
be retried using the library's Refresh action. Unchanged library polls preserve the displayed list.

On A, choose **Host a session**, select its LAN interface and click **Start hosting**. On B, choose
**Connect to a session > Discover LAN hosts**, select A and click **Request control**. A's session
window opens for the request: click **Allow once** to connect, **Always allow** to whitelist that client
(future requests are granted automatically) or **Block** to ignore its future requests. Remembered
clients (matched by name and IP, which is unauthenticated) are listed under **Allowed and blocked clients**,
where they can be removed. The first discovered host is selected automatically. Nothing needs to be copied,
and no pairing code or fingerprint checkbox is needed for this discovered-host flow. Repeat the same
discover/request/approve steps for a new hosting session. Only one remote client is admitted. Each request opens a modal approval popup; a new request from a
reconnecting client is still offered while the old one seems connected, and allowing it closes the old connection.

This convenient flow is for a **trusted LAN**. Discovery names and certificate fingerprints are
unverified hints, not proof of identity: approve only an expected request on the intended A, checking
its claimed client name and source address. TLS is encrypted and strictly pinned to the selected
session certificate, but discovery alone does not protect B against an attacker impersonating A on
the LAN. Requests are rate-limited, expire and expose no workspace data before A approves. For
independent host verification or when discovery is blocked, expand the advanced/manual controls:
copy A's connection details, verify the fingerprint separately, enter its short-lived pairing code
and connect; A still approves. Access is remembered only if A picks Always allow or Block.
In the packaged Windows app, **Start hosting** checks private-LAN permission. If needed, choose
**Allow private LAN access** and approve Windows' administrator prompt; no commands need to be copied.
Permission is restricted to this executable, the selected control/artwork TCP ports and discovery UDP
7074, inbound from the local subnet on Private networks. It persists across restarts and updates at
the same location. Moving the app or changing ports may require permission again. **Private LAN access**
also offers **Check access** and **Remove permission**. Nothing changes without explicit consent;
**Host without changes** uses existing permissions. Public network profiles are never changed or
granted access automatically. Only mark trusted networks Private in Windows Settings.
Development/shared Java runtimes and other operating systems do not configure Windows rules.
Configured rules do not prove reachability: VPNs, security software and guest-network isolation can
still block connections. Manual connection still works if only multicast is blocked.
Windows checks keep PowerShell status separate from diagnostics, so module-loading progress is not
reported as a failed permission check.
This is a LAN feature, not an Internet relay; no router ports are forwarded.

While connected, B uses A's settings in memory. Settings changes persist on A only; **Browse host**
selects A's map/audio library directories, while source-file pickers stay local to B. Imports upload
verified content to A, maps and tokens are created/saved in A's libraries, and exports download to a
destination chosen on B. Map context menus, tags, recents, multilevel management, token editing and
audio library/playback/preparation controls target A. Ambient audio, music and overlay playback stay on A, but the audio editor/library preview on B downloads A's track and plays it locally so you can prepare the library from B. A modal "Downloading audio" popup with an MB counter blocks the editor until the download finishes.
Save/Ctrl+S waits for completed edits and host persistence; a new map asks on B for a name and a
folder in A's library. Switching away from an unsaved new host map offers Save, Discard or Cancel.
Handouts share A's presentation and recent library without replacing B's local recent list.
Asset uploads and downloads show a temporary progress card (direction and MB counter) at the bottom
center of the map view, above the status bar; the DM Link tab no longer shows transfer progress.

Connection loss pauses host-directed edits. Use **Reconnect** to recover the exact outcome of an
in-flight command, not replay it as a new action. Unsent commands are cancelled explicitly; expired
receipts or a restarted host require resynchronization. **Disconnect** restores B's original local
workspace, settings, selections and history; B's local audio remains stopped. On A, **Revoke** removes
B's access, while **Stop hosting** closes both listeners and ends the session. Host certificates and
credentials are session-only: rediscover and request approval after restarting hosting. Only the
optional manual route requires new copied/verified connection details.

Remote control is **strictly opt-in**. Standalone editing starts no remote listeners, discovery,
polling, remote library scans, synchronization snapshots or artwork hashing. Opening setup alone does
not host or connect. Only submitted non-secret names, addresses and ports are remembered in
`remote-session/setup.properties` next to the settings file. Session storage has 1 GiB per-asset,
16 GiB stored-object and 2 GiB staging limits. Assets use a separate authenticated TLS channel,
verified hashes, progress and bounded transfer concurrency; metadata catalogs are bounded and report
oversized/inaccessible libraries explicitly rather than showing an empty success.

Each PC renders its own DM view, so B still needs enough graphics memory for the active map. A still
performs authoritative persistence, imports, image preparation, audio analysis and player rendering;
a stronger B does not offload those jobs. Initial artwork/library transfers and large imports are
the main bandwidth/IO costs. Unchanged polls reuse accepted state and library metadata is cached;
use Refresh for external library-file changes. Physical LAN latency and throughput depend on the
machines, map sizes and network and are not guaranteed by loopback tests.
