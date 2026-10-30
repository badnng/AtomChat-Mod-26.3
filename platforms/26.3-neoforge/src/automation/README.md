# Development client automation

This source set is loaded only by `runAutomation`. It must never be included in
the release JAR. The normal client entry point installs it reflectively only
when `atomchat.automation=true`.

Run from the repository root with JDK 25:

```powershell
python tools/run_neoforge_automation.py --java-home <JDK25>
```

The script creates a fresh `build/automation-<timestamp>-<pid>` directory. The
probe refuses to modify any game directory whose last path component does not
start with `automation`. Configuration, world, media and history fixtures stay
inside that run directory. No user saves are reused or deleted. Reports are
updated after every check; a client watchdog and a separate outer process
deadline prevent an indefinitely stalled run.

The client creates an integrated-server test world through Minecraft's own
world-loading APIs and waits for the real client player, world and connection.
It runs the production panel navigation, send pipeline, command connection,
reaction networking, profile signature publication, media upload/download,
notification command transport and queue, settings persistence, appearance
presets and all built-in palettes, own profile avatar/banner upload and clear,
hosted GIF download and frame changes, emote file add/reopen/remove, draft
restoration, disconnect cleanup, and history reload after rejoining the same
world. Sound checks verify every bundled cue resource, actual sound-engine
channel-start events from production playback, and master-switch suppression;
they do not judge how the result sounds to a listener. Reflection is confined to this source set to reach
existing private production seams; stores are not populated with fake events.

The automation-only mixin requests `SDL_WINDOW_HIDDEN` when the GPU backend
creates the window; a first-tick SDL hide is also applied as a fallback. This
needs a functioning graphics driver and desktop session; it is not a
display-free GameTestServer. `java.awt.headless` does not make Minecraft
headless. Set `atomchat.automation.hideWindow=false` on the Gradle run for a
visible diagnostic session.

`PASS` on a step means its stated condition succeeded. Page navigation checks
require settled panel/page animations, a valid Skia surface, nontransparent pixel
samples and at least two new GUI frames actually rendered after settling. A separate check requires successful
world GPU readback/blur generation. PNGs of the real panel raster are saved next
to the report. The probe clicks real panel buttons, all visible settings switches,
and crop-confirm controls, and verifies their effects. The separate `goldenCompare`
task compares deterministic CPU pages with the pre-migration baseline.
The report is `PARTIAL` when a scenario is explicitly skipped. The wrapper exits 3 for `PARTIAL`; `--allow-partial` accepts that result
explicitly. Missing reports, running reports, failed steps and Gradle errors
always fail.

For a failed history reload after a successful history write, the wrapper accepts
`--history-run <previous-build/automation-directory>`. It verifies the original
report's history-write evidence and generated fixture identity, preserves that
report, and writes a separate `history-diagnostic-*` result. This diagnostic uses
the actual backup-and-join button only for the stock experimental warning of the
isolated fixture. Unknown dialogs remain untouched; timeout details include
their class/title and connection state. A diagnostic `PASS` applies only to its
history-only mode; the default invocation still runs the complete scenario list.

Not yet verified by this single-client probe:

- Broadcast delivery to a second client, real private messages, received pokes,
  remote avatar/banner synchronization and resource-pack updates are covered by
  the pair probe below, rather than this single-client run.
- Full GPU image comparisons, scroll/selection dragging, system clipboard,
  operating-system file dialogs, IME, perceived sound and animation trajectories.
- Slider/colour/destructive setting gestures, external image-host fallback,
  vanilla-client compatibility and every emote-panel editing gesture.

Run the two-client probe against an isolated, loopback-only integrated LAN server:

```powershell
python tools/run_neoforge_pair.py --java-home <JDK25>
```

It checks public/private messages, private draft isolation through the native chat
entry point, reactions, pokes, remote profile media, pack synchronization and
host/guest permissions. It exports ModDev's actual launch specification before
starting the two JVMs, so neither holds a Gradle project lock during the run.
Each role writes its own report; `pair-report.json` combines both results.

For a real dedicated-server distribution smoke test:

```powershell
python tools/run_neoforge_server_smoke.py --java-home <JDK25>
```

This loads only the main mod in an isolated `--nogui` server, waits for startup,
checks console commands, sends `stop` and requires a normal exit. It tests
development outputs, and does not claim to have loaded a packaged release JAR.

All probes keep their fixtures and reports under `build/automation-*`; their
classes, metadata and mixins are absent from the release JAR. The normal JUnit
suite remains part of `build` and does not load the automation mod.
