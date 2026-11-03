**What the settings page holds, how to change your avatar and wallpaper, and what the many keys in the config file mean.**

## The settings page

The third tab at the bottom, **Settings**, contains **Appearance / Chat / Privacy & Blocking / About**. The default AtomChat interface keeps its Windows 11 style 2×2 tile home. MIUIX presents the same settings as Miuix preference pages inside the Minecraft panel.

Settings changes take effect without a game restart and are saved automatically when committed. In AtomChat, colour options include a preset palette, a `+` custom colour picker and a live preview swatch. MIUIX keeps Miuix's default typography, semantic colors, and component styling, overriding only the primary accent. Configured chat-bubble fills and text colors still apply to message content.

## Appearance

| Option | Notes |
|---|---|
| Theme preset | `Frosted` / `Modern`, one click each. Presets change appearance values but do not change the interface style or the MIUIX navigation choice. |
| Interface style | In AtomChat, use **Settings → Appearance → Theme tab → Interface style**; in MIUIX, use **Settings → Appearance → Interface style**. Choose `AtomChat` (the default) or `miuix`. The local change applies immediately and is saved; switch back at any time. MIUIX is available in the 26.3 NeoForge build and uses the official Miuix Compose components. It keeps the configured accent and chat-bubble colors where those apply. |
| Miuix theme | In MIUIX **Settings → Appearance**, choose `Light` or `Dark`. This selects the Miuix semantic palette and updates the panel color used to choose the scheme. |
| miuix bottom navigation | In AtomChat **Appearance → Theme**, or MIUIX **Settings → Appearance**, choose `Icons only`, `Icons and text` (the MIUIX default), `Selected label`, or `Floating`. It is saved independently and applies only in the MIUIX interface. These modes use Miuix `NavigationBar` and `FloatingNavigationBar`; the floating option has a blurred capsule background. |
| Background blur | Blurs the world behind the panel; **mutually exclusive with a custom wallpaper**. MIUIX samples the live world at up to 60 Hz on OpenGL, checking an asynchronous 96×54 probe for changes and refreshing at least every 125 ms. On Vulkan it reads only the panel region asynchronously, reusing one staging buffer with at most one request in flight and a 30 Hz cadence. Miuix applies texture blur to the page and progressive texture blur to the top bar and composer when runtime shaders are supported. |
| Panel opacity | 0–100%, applied to the base colour whether blur is on or off; MIUIX also uses it for page glass tint and wallpaper opacity. The top bar uses its own collapse-dependent tint. |
| Panel width | 400–600 (design pixels) |
| UI scale | x0.75 – x1.50, scaling the whole interface (layout and hit testing included) |
| Message entry animation | The QQ-style slide-in from the side plus fade |
| Animations | Turns off decorative motion (entry/exit, page push, nudge, scroll snapping); functional feedback such as hover highlights is **not** affected. |
| Double-click avatar to nudge | On / off |
| All interface colours | AtomChat exposes panel background / own bubble / other bubbles / bubble text / secondary capsule / primary and secondary text / card / outline / accent, each with a live preview. MIUIX uses the configured accent and chat-bubble values; it keeps Miuix's stock semantic colors for its components. |
| Panel corner radius | The in-game slider adjusts the panel perimeter. MIUIX applies this to the outer panel frame; its internal components keep Miuix's standard radii. |

MIUIX root tabs share one scaffold and keep the native bottom-navigation indicator; only the page content crossfades over 160 ms. The existing `pageNavStyle` (`SLIDE` / `ZOOM`) applies to secondary-page push/pop. `SLIDE` uses a 500 ms push/pop with quarter-width parallax. `ZOOM` shrinks and fades the outgoing page over 180 ms, then starts the incoming page after 180 ms and scales it from 110% while fading in over 260 ms. The Animations switch disables these transitions.

The large title collapses with page scrolling, including in chat. Its progressive glass follows the collapse state, fading the surface tint into a dim layer with a soft edge below the bar. Panel opacity does not scale this top-bar tint.

When a renderer session is rebuilt, temporary popups close and uncommitted IME preedit is canceled; saveable scroll position is restored from memory, while navigation and chat drafts remain in the Java host. A size-only target change in the same GPU session keeps the Compose scene alive, including its temporary overlays.

MIUIX follows the user-approved stock Miuix screenshot. It uses Miuix's default typography and semantic colors, with AtomChat's accent as the only theme override; configured bubble colors remain active for messages. Legacy card tint/color, panel outline, primary/secondary text, and capsule controls stay in the AtomChat interface and keep their saved values when switching styles. The MIUIX Appearance page contains its theme mode, navigation, and panel-radius controls.

### Custom avatar

`Appearance → Custom avatar` → pick an image → you land in the cropper:

- **drag** to pan, **scroll** to zoom around the frame, **double-click** to reset;
- the output is a 256-pixel square PNG in `.minecraft/config/atomchat/avatar/`;
- players on the same server who run AtomChat **receive your avatar automatically** (changing it broadcasts a refresh immediately). If they do not run it, or the server turned hosting off, it degrades silently to the skin-based avatar.

### Custom wallpaper

`Appearance → Custom wallpaper` → pick an image → the same cropper. The image is downsampled (long edge ≤ 1024 pixels) and stored in `.minecraft/config/atomchat/wallpaper/`; `png` / `jpg` / `jpeg` / `webp` are supported.

A wallpaper and background blur are **mutually exclusive**; with both configured the wallpaper wins, and panel opacity is used to darken it so the text stays readable.

## Chat

MIUIX chat follows the approved screenshot layout: message text is 17 sp and bubbles are capped at 320 dp. Own messages align to the right without an avatar or sender name. Ungrouped messages from other players include an avatar and sender name. A timestamp, when present, and More action sit in the bubble footer. The composer puts the text field on the left and Send on the right of the same row; a second row holds emoji, sticker, phrase, and image actions in that order.

For eligible other-player avatars when poke is enabled, a single click waits up to 300 ms so a second click can send the existing poke action instead of opening the profile. Right-clicking a message or selecting More opens the existing message actions.

The world-chat filter keeps the original cycle: All → System → Players. Its original filter, announcement, and player icons and their tints follow the selection, with a short glyph fade.

MIUIX profiles keep the original banner, overlapping avatar, signature, ping/session/mined statistics, and role/server/UUID information in Miuix cards. Editing your own profile and actions on other players retain the existing availability rules and backend actions.

| Option | Default | Notes |
|---|---|---|
| Image messages | on | Off means no download and no cache, with a green `[图片]` placeholder |
| Timestamp dividers | every 5 minutes | The first message always shows one; `0` turns them off |
| Anti-spam merging | on | Consecutive identical messages collapse into one with a count |
| Compact grouping | off | Within five minutes, a sender's consecutive messages keep only the first one's avatar and name |
| Keep chat history | off | When on, stores per server / world in `.minecraft/config/atomchat/history/` (JSONL) and restores it on rejoining |
| History retention | 7 days | `0` means forever |
| Notifications (@ / quotes / private chat) | on | Banners and sounds toggle separately, with an overall volume |
| Teleport mode | `auto` | Cycles between `/tp`, `/tpa` and `auto`; `auto` probes the server's command tree |

## Privacy & Blocking

- **Blocked list:** a visual list (avatar + name + a one-click unblock);
- **Hide blocked players' messages:** on by default. Turned off, their public messages are visible again while private chat stays blocked;
- **Accept server-offered content:** on by default. Turned off, the client stops requesting emotes / phrases / server identity and clears what it has already loaded.

## About

Version, licence, repository links, the listed third-party components (Skija / Skia / FlatLaf), and two actions. The full bundled runtime dependency list is in [`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md).

- **Clear image cache** — clears only the image cache under `<game dir>/atomchat-data/`, leaving emotes, wallpaper and avatars alone;
- **Debug mode** — writes the avatar colour sampling into `.minecraft/config/atomchat/debug/` as PNGs, for chasing avatar colour problems.

## Config file and where data lands

Anything you can change in game can also be edited in the file, which is what you want for bulk edits or pre-seeding a pack. The main config is **`.minecraft/config/atomchat/atomchat-client.json`** (generated on first launch).

> Editing the file by hand needs a **game restart**, with one exception: `chatTemplates` / `whisperTemplates`, which are re-read every time the chat screen opens.

| Path | Contents |
|---|---|
| `config/atomchat/atomchat-client.json` | The client's main config |
| `config/atomchat/avatar/` | Custom avatars (256px PNG) |
| `config/atomchat/emotes/` | Your own emotes (unlimited by default, `emoteMax` sets a cap) |
| `config/atomchat/server-emotes/` | **Server only:** the distribution source handed to clients |
| `config/atomchat/wallpaper/` | Custom wallpaper |
| `config/atomchat/history/` | Chat history (off by default, one file per server / world) |
| `config/atomchat/debug/` | Avatar sampling PNGs exported in debug mode |
| `<game dir>/atomchat-data/` | Image cache, server-offered packs, other players' avatar data (500 files / 100 MB / 7 days unused) |

The server's own config and data live under the server's game directory; see the [Server Owner Guide](Server-Owner-Guide).

## The full client config

| Key | Default | Notes |
|---|---|---|
| `panelWidth` / `panelHeight` | `440.0` / `780.0` | Panel size in design pixels, multiplied by UI scale internally |
| `panelOpacity` | `0.93` | Panel background opacity, 0–1 |
| `blurEnabled` | `true` | Background blur (mutually exclusive with a wallpaper) |
| `uiScale` | `1.0` | UI scale, x0.75–x1.50 |
| `animationEnabled` | `true` | Master switch for decorative motion |
| `messageEntryAnimation` | `true` | Message entry animation |
| `avatarPokeEnabled` | `true` | Double-click an avatar to nudge |
| `hideBlockedMessages` | `true` | Whether a blocked player's public messages are dropped outright |
| `cardTint` | `0.235` | AtomChat card surface blend; not exposed by the stock MIUIX appearance |
| `cornerRadius` | `20.0` | AtomChat container radius; MIUIX applies it to the outer panel frame only |
| `themeName` | `""` | The preset applied last (`frosted` / `modern`) |
| `uiStyle` | `"ATOMCHAT"` | Local interface presentation: `ATOMCHAT` (default) or `MIUIX`; independent of colour presets |
| `miuixNavigationStyle` | `"ICON_AND_TEXT"` | MIUIX bottom navigation: `ICON_ONLY`, `ICON_AND_TEXT`, `SELECTED_LABEL`, or `FLOATING`; only affects `MIUIX` style |
| `panelBgColor` | `0xEE16191F` | Panel background colour; Miuix derives its light/dark palette from this value |
| `panelOutline` / `panelOutlineColor` | `true` / `0xFFFFFFFF` | AtomChat panel outline toggle and colour; retained when MIUIX is selected |
| `ownBubbleColor` / `bubbleTextColor` | `0xFF1E90FF` / `0xFFFFFFFF` | Own bubble: fill / text |
| `otherBubbleColor` / `otherBubbleTextColor` | `0xFF2C3E50` / `0xFFFFFFFF` | Other players' bubbles: fill / text |
| `textPrimaryColor` / `textSecondaryColor` | `0xFFFFFFFF` / `0xDCAAAABA` | AtomChat primary / secondary text colors; MIUIX uses stock semantic text colors |
| `secondaryCapsuleBg` / `secondaryCapsuleText` | `0x962C3E50` / `0xDCAAAABA` | AtomChat system lines, timestamps and quote capsules |
| `cardColor` | `0xFFFFFFFF` | AtomChat card surface colour |
| `accentColor` | `0xFF4A90E2` | Accent (send button, quote bar, scrollbar, …) |
| `imageMessagesEnabled` | `true` | Whether image messages are fetched and rendered |
| `timestampIntervalMinutes` | `5` | Timestamp divider interval in minutes; `0` turns them off |
| `antiSpamEnabled` | `true` | Merge consecutive identical messages with a count |
| `compactMessagesEnabled` | `false` | Compact grouping within five minutes per sender |
| `mentionBannerEnabled` / `mentionSoundEnabled` | `true` | Banner / sound when you are mentioned or quoted |
| `whisperBannerEnabled` / `whisperSoundEnabled` | `true` | Banner / sound on an incoming private message |
| `notifyVolume` | `1.0` | Notification volume, 0–1 |
| `mentionRequireAt` | `false` | `true` counts only an explicit `@name` as a mention; `false` also counts a bare name |
| `serverPacksEnabled` | `true` | Whether to accept server-offered emotes / phrases / identity |
| `emoteMax` | `0` | Cap for your own sticker folder, `0` = unlimited; takes effect when the chat screen reopens |
| `chatHistoryEnabled` / `historyRetentionDays` | `false` / `7` | Keep chat history / days to keep it (`0` = forever) |
| `teleportCommandMode` | `"auto"` | Teleport command mode: `auto` / `tp` / `tpa` |
| `quickPhrases` | `[]` | Quick phrases (up to 20, each ≤256 characters); a fresh config is seeded with `/atomchat gui` once, and deleting it sticks |
| `guiTipSeeded` | `false` | Whether that seed has run (internal marker) |
| `blockedPlayers` | `[]` | Blocked list (real player names) |
| `chatTemplates` / `whisperTemplates` | `[]` | Templates that recognise non-standard chat formats, with the placeholders `{name}` `{display_name}` `{prefix}` `{suffix}` `{sep}` `{content}`; reopen the chat screen after editing |
| `debug` | `false` | Debug output and avatar sampling PNGs |
