# USB/IP input passthrough — locked terminology and design

**Decided 2026-10-03 by Nik and Rias.** This file exists because "host" and "client" mean *opposite* things
depending on which layer you are talking about, and we both got tangled by it. Read this before designing,
naming a variable, or writing UI copy in this area.

## The two machines

| Thing | Machine | Plain meaning |
|---|---|---|
| **ArtMoon** — streaming **client** | mini PC (`niks-minipc`) | Displays the stream. Owns the device list and the toggles in Input settings. |
| **ArtLight** — streaming **host** | gaming PC (`niks-gaming`) | Serves the video. Receives the devices. |
| **USB/IP host** | mini PC | The machine the keyboard and mouse are physically plugged into. Serves them out. |
| **USB/IP client** | gaming PC | The machine that attaches the served devices. |

## Why the collision is real

- USB/IP names the role **after the device side**: the exporter's kernel module is `usbip-host.ko`; the importer
  is `vhci-hcd.ko` — a *virtual host controller*.
- `usbip-win2` titles itself **"USB/IP Client for Windows"**.
- Meanwhile in streaming, ArtLight is "the host" because it **serves the video**.

So on this pair of machines: **streaming-host = USB/IP-client** and **streaming-client = USB/IP-host** — exactly
inverted.

## Rule

Never write `host`, `client` or `server` bare — not in the UI, not in variable names, not in comments, not in
prose. Use:

- **ArtMoon** / **ArtLight** for the products and the streaming roles
- **exporter** for the machine the device is plugged into
- **importer** for the machine the device arrives on

## The verbs, and which side owns them

| Verb | Side | Meaning |
|---|---|---|
| **bind** | exporter (ArtMoon) | Make a device shareable. Persistent, privileged, local. |
| **attach** | importer (ArtLight) | Take a shared device. Moves it — the exporter loses it. |
| **detach** | importer (ArtLight) | Give it back. |
| **unbind** | exporter (ArtMoon) | Stop sharing it. |

## The agreed flow

1. **ArtMoon** enumerates its local devices (Win32 on Windows, sysfs on Linux — no network needed to draw the
   list) and shows them in **Input settings**.
2. The user **toggles** the devices to share. ArtMoon **binds** the toggled ones. This is a privileged local step
   that ships as a service inside ArtMoon's own installer — the same shape as the service `usbipd-win` installs.
   The user never sees a CLI.
3. **Stream starts.** ArtLight enumerates what ArtMoon is exporting and **attaches** the toggled ones.
4. **Stream stops.** ArtLight **detaches** — the devices return to ArtMoon's machine.
5. Un-toggling in ArtMoon **unbinds** (or simply stops exporting).

**Ordering rule:** stream up first, attach second. Attaching *moves* the device, and if the exporter is the machine
the user is sitting at, attaching before the stream is up takes their keyboard and mouse and they cannot start
anything.

## Requirements each product carries

- **ArtMoon (exporter):** local device enumeration, the toggle UI, and a privileged local service to bind. It
  also needs the `usbip` client and the `usbip-host` kernel module **on its own machine** — the service exports a
  device by calling `usbip bind`, so a machine without that tool can export nothing, however good the UI is.
  Since 1.8.0 the client ships inside the Linux AppImage and the Windows installer, so only the kernel module is
  left to the host; the helper loads it, and says so plainly when it cannot.
- **ArtLight (importer):** a USB/IP client stack on the host machine — the UDE driver on Windows, `usbip` +
  `vhci-hcd` on Linux. This is ArtLight's own dependency, not ArtMoon's.

## Platform support

All four orders are supported upstream. Only Windows→Windows is proven on these machines so far.

**Proven in the wild, 2026-10-02:** Nik attached the mini PC's keyboard and mouse to the gaming PC and played
**Aion 2** — a game that drops injected input — and it registered his inputs normally once the devices were
passed over. That is the whole justification for this feature, demonstrated end to end before any code was
written.

| exporter | importer | upstream? | evidence |
|---|---|---|---|
| Windows | Windows | ✅ proven in use | `usbipd-win` + `usbip-win2`; Aion 2 accepted the real HID, 2026-10-02 |
| Windows | Linux | ✅ documented | usbipd-win README: `usbip attach --remote=<HOST> --busid=<BUSID>` |
| Windows | WSL 2 | ✅ documented | `usbipd attach --wsl --busid=…`, no admin required |
| Linux | Windows | ✅ documented | usbip-win2 README: kernels 4.19–7.0, server protocol 1.1.1 |
| Linux | Linux | ✅ | in-kernel both ends |
| macOS | anything | ❌ | no exporter, no client |

## Network

TCP **3240**. Reachable over any IP path, but the `usbipd-win` installer's firewall rule is
`RemoteAddress = LocalSubnet`, so it is **LAN-only by default**; a remote client needs the tunnel's subnet added
to that rule. Keep it inside a tunnel — USB/IP is plaintext with no authentication, so anyone who reaches 3240 and
knows a busid gets the device. Never port-forward it.

Attaching is **non-persistent**: re-attach after a reboot, a device reset, or a replug.

## The firewall, and the shape an authenticated future would want

**Seed, 2026-10-04, Nik and Rias.** Not a decision — a note so it does not get lost.

On Linux, ArtMoon's own helper is what opens 3240, because nothing else does: the `usbip` package ships `usbipd`
disabled and opens no port, and the `usbipd-win` installer's `LocalSubnet` rule has no Linux counterpart. The rule
the helper writes is deliberately the narrowest one that works:

- it names **one address** — the machine actually streaming to us — never a subnet, never `Anywhere`;
- it exists only while that session is live; the reconcile that ends the session closes the port again, and so
  does turning sharing off;
- it is removed by matching the **exact string this program generated**, never by pattern. Firewalld rich rules
  carry no comment field, so nothing on the face of one says who wrote it — and a rule a person wrote by hand for
  their own purposes has to survive us touching the same port. On that side the rule is also added with a
  lifetime, so it closes itself rather than being guessed at.

The transferable part is not the ufw calls. It is the rule underneath them: **open for the one thing you can name,
and only while you are serving it.** The helper already refuses to open the port for a machine it cannot point at,
and that refusal is the seam.

That is the shape authenticated WAN sharing would want, if it is ever built. What would change is what counts as
a *name*: an address stops meaning much once the importer can roam or sit behind a NAT, so the peer would be the
authenticated identity instead. The discipline is identical, and the two functions that would change
(`allowPortFor`, `revokeStaleRules`) already take the peer as an argument rather than working it out for
themselves. Swapping the key, not rewriting the design.

Honest state of it: the ufw half is proven — 11 assertions in a private network namespace with a real veth pair
and a real connection from the far end, including that a hand-written rule for 3240 survives every step. The
firewalld half is **by construction, not by test** — there is no firewalld machine here to exercise it on.

## Known trap

An empty `Persisted:` table in `usbipd list` does **not** clear a repeatable `Device busy (already exported)`.
That message can mean the **vendor software** on the exporter (Razer Synapse and friends) is holding the HID
interfaces open. The engine keeps them, so the exporter cannot release the device — and it reports the wrong
cause. Stop the vendor engine on the exporter and the same attach succeeds.

## The privileged service — how the desired set reaches it

**Decided 2026-10-03.** `bind` is refused to a normal user (verified on the mini PC: a limited token gets
`LastTaskResult : 10`), so ArtMoon cannot bind for itself. Each platform carries a small privileged local
service that owns the bind step, and the app talks to it. **The user never sees a command line.**

### Linux

The helper is a static binary at `/usr/libexec/artmoon-input-service`. The AppImage carries a copy at *the same
relative path it occupies on the system* (`usr/libexec/…`), so placing it is a copy and not a remapping — and a
normally-installed ArtMoon resolves the bundled path to the real system path, which exists only once the helper
is genuinely there. That relationship is what makes the app's "should I offer to set this up" answer honest.

Setting it up is one administrator prompt: `pkexec install -m 0755 -o root -g root <staged> /usr/libexec/artmoon-input-service`.

`<staged>` is deliberately not the copy inside the AppImage. An AppImage runs from a FUSE mount, and the AppImage
runtime mounts it **without** `allow_other` or `allow_root` — verified on Niks-z13 on 2026-10-03, mount options
`ro,nosuid,nodev,relatime,user_id=1000,group_id=1000`. Only the mounting user may traverse such a mount, and root is
refused too, because `CAP_DAC_OVERRIDE` does not bypass the FUSE mount-owner check. Handing the in-mount path to an
elevated `install` therefore fails with `cannot stat: Permission denied`. So the app copies the helper into a
directory only that user can reach — 0700 under `$XDG_RUNTIME_DIR` — and the elevated step reads from there. No other
principal can redirect what root reads; the user alone can, and they are the one approving the prompt. Hardening that
last step means handing the payload to pkexec over stdin rather than as a path, which needs evidence that pkexec
carries stdin through — not established, so not what ships.

`install(1)` specifically, not a shell command and not a script carried in the bundle. Whatever is handed to
pkexec runs as root, so it must be a system binary that takes two paths and does one thing — an approver can see
exactly what is being authorised. Approving a script inside a user-writable mount would authorise *that script's
path*, and anything able to write there could then get a program of its choosing run as root. `install` also
applies mode and owner itself, so the helper never exists at its system path with the wrong ownership, not even
briefly.

**How the desired set reaches the helper — and why Linux needs no daemon:**

The helper refuses to act without administrator rights and the app is unprivileged, so every reconcile has to
arrive through `pkexec`:

    pkexec /usr/libexec/artmoon-input-service reconcile --want <busid>... --local <busid>...

That needs no password prompt because of a second file placed by the same one-time setup: the polkit action
`org.artmoon.input-service.reconcile` (`service/org.artmoon.input-service.policy`), which grants it to the
**active local session** with no authentication. So the single approval the user gives is the one that placed the
helper and the policy — not one per toggle. The action is bound to that one program by its
`org.freedesktop.policykit.exec.path` annotation, so it cannot be aimed anywhere else, and `allow_any` and
`allow_inactive` are both `no`: a remote session, or one nobody is sitting at, cannot use it.

This is why Windows and Linux differ in shape and not in intent:

- **Windows needed a pipe.** A running service refuses `sc start` (error 1056), so start arguments can only ever
  deliver the *first* desired set. Hence a channel the app can send a changing set down.
- **Linux needs no channel.** `pkexec` carries a fresh argument list on every call, which is exactly what
  `sc start` could not do. A daemon here would be a moving part solving a problem this platform does not have.

**Setuid is ruled out, and the reason is one line of the helper.** It runs `usbip` by bare name through `execvp`,
and `execvp` resolves a bare name through `PATH`. As a setuid-root binary that is a root hole by construction: a
program called `usbip` in a directory earlier in `PATH` runs as root. The helper now sets its own `PATH` before it
can spawn anything, so it no longer depends on its caller for that — but setuid would still make the correctness of
this one program the only thing between a local user and root. Polkit grants the same privilege with the system's
own session scoping, an audit trail, and no setuid binary on the machine.

**The outcome is not the request.** `setWanted()` asks, then has to *look again*: the label a user reads belongs
to the machine, not to what we asked for. The first Linux end-to-end test on the z13 proved the privilege path and
caught this in the same minute — the journal shows the bind landing (`usbip-host 3-10: register new device`) while
the row still read "Not shared yet", because the code re-marked the rows from the remembered intent instead of
re-reading. It re-reads now, and takes one small bounded second look (`kSettleIntervalMs`) because the kernel
registers the device a heartbeat after the helper returns. That is the same rule the rest of this design is
already held to — `canShare` and `canInstallService` are both decided from files, never from an exit code —
applied to the aftermath of our own action.

Verified on the z13, 2026-10-03: toggle on → the row reads **Shared** within a second, toggle off → the device
is released, and in both directions the kernel agrees with the label.

**The list re-reads itself — the follow-up this paragraph used to record is closed.** `refresh()` ran once, at
construction, so a device plugged in or pulled out stayed invisible until the app restarted. The watch timer is
the fix, and it never stops — it only changes speed: 5 s with nothing switched on (`kIdleListIntervalMs`), 1 s
while something is wanted but not yet bound (`kPeerWaitIntervalMs`), 5 s while something is shared
(`kPeerIntervalMs`). Measured on the z13, 2026-10-05: a device plugged in appears, and one pulled out disappears,
with no restart. There is deliberately no hook on the Input tab becoming visible — the timer is the only thing
that re-reads, and a visibility hook would be a second path to the same answer.

### Windows

The installer creates the service (`ArtMoonInputService`) — the same shape as the service `usbipd-win` installs
for itself. The app never binds; the service does.

**How the desired set reaches the service — and the two options that are ruled out:**

- **Not `sc start … reconcile`.** A service that is already running refuses a start with error **1056**, so start
  arguments cannot carry a *changing* desired set. They can only ever deliver the first one.
- **Not a file.** Any path that a normal process can write, the service must not read as instruction. A file the
  user's own session can rewrite would let anything on the machine choose which devices are offered over the
  network — the same rule the Linux side is already held to. The desired set is *requested and validated*, never
  trusted as stored state.
- **A local named pipe, created by the service**, with an explicit DACL granting the interactive user and
  administrators, and `PIPE_REJECT_REMOTE_CLIENTS` set so that a remote machine cannot reach it even if the pipe
  name is known. The app sends the desired set on each change; the service validates every busid with the same
  guards as the Linux helper and refuses anything it does not recognise.

On both platforms the service keeps **no state of its own** that can disagree with the Input tab: it reconciles,
it does not remember. `usbipd list` (Windows) and `/sys/bus/usb/drivers/usbip-host` (Linux) stay the authority on
what is *actually* shared, which is why the tab can show "not shared yet" and mean it.

## How it shipped — reviewed 2026-11-07

This file was written as a design on 3 October 2026, and the work landed over the month after it. What follows is
where each piece actually lives and what was deliberately left out, so nobody has to re-derive it from the history.

| Piece | Where it lives | First shipped |
|---|---|---|
| Device list and the Off/On switches | ArtMoon → **Settings → Input** (`app/usbip/`) | ArtMoon 1.8.0 |
| Privileged `bind` on the exporter | `artmoon-input-service` — a pipe on Windows, polkit plus `pkexec` on Linux — carried by both ArtMoon installers | ArtMoon 1.8.0 |
| Vendor-engine handling | `VendorEngine` in ArtMoon: the engine's processes are stopped for the take and started again after a ten-minute linger, with a record file so a crash still puts it back | ArtMoon 1.8.0 |
| Take and give-back on the importer | ArtLight Server, `src/usbip_input_session.*` and `src/platform/*/usbip_input.cpp`. The session owns the holder, so every end path releases — a clean stop, a crash, a stop racing a start | ArtLight 1.5.0 |
| The importer's own switch | Web UI → **Settings → Input → Client input** (`input_usbip_enabled`) | ArtLight 1.5.0 |

**No new control verbs were needed**, which retires the sequencing question with the clipboard work. Two facts
made the wire extension unnecessary: the exporter is the stream's own source address, so the importer already
knows where to ask; and the tick in ArtMoon is what makes an export exist at all, so "what the exporter is
offering" *is* the device list. ArtLight probes `usbip list -r` when the session starts and takes what is offered —
an empty `input_usbip_busids` means everything offered, and a non-empty one is an allowlist.

**Not built, deliberately:**

- **Per-host device memory.** The remembered toggles are keyed on the device (`usbIpRememberedDevices`), not on the
  machine being streamed to, so the same devices are offered to every importer. That matches the single-importer
  case this was designed for; splitting it per host is a preference change, not a bug fix.
- **A status pane in ArtLight Control.** The importer's switch lives in the Web UI. Control has no USB section.
- **Phase 3 — removing the last elevation prompts.** Not attempted. Setup is one deliberate approval on each
  platform, not an approval per use, and nothing since has made that feel expensive.

**Double input needs no rule, because the device moves.** When a device is taken, the exporter's kernel no longer
has it, so ArtMoon's synthetic input path has nothing to forward for it. The case that would double is *half* a
device — a keyboard whose second HID interface stayed local — and the answer there is the same one the
twice-enumerating-mouse trap above already demands: share a multi-interface device whole.

