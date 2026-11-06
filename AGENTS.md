# AGENTS.md — Simple TV Launcher

Single-module Android TV launcher (Kotlin + platform Views, no Android Studio). Product spec:
`.opencode/instructions.md` — note its idle-screen-saver section is **not implemented yet**.
`AGENT.md.old` is a superseded scaffold plan; ignore its file tree.

Hard constraints: no Compose, no Leanback, no AppCompat; `androidx.recyclerview` is the only
runtime dependency (APK budget ≈ 600 KB of code/UI; the five built-in wallpapers in
`res/raw` add ~430 KB on top, so a clean debug build is ~900 KB). Everything must be D-pad reachable.

## Build

`java` is **not on PATH**; Gradle fails without this:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew clean assembleDebug      # Gradle 8.9, AGP 8.5.2, Kotlin 2.0.21, JDK 17
ls -lh app/build/outputs/apk/debug/app-debug.apk   # ~900 KB from a clean build (430 KB wallpapers)
```

- Read APK size only after `clean`: incremental packaging reports inflated sizes (~705 KB). R8
  already runs on debug (`isShrinkResources=false`); release additionally shrinks resources.
- `local.properties` hard-codes this machine's SDK path and is gitignored; never commit it.
- No tests and no CI. Verification = build + install/observe on the TV.
- Lint `QueryAllPackagesPermission` is disabled on purpose (a launcher needs `QUERY_ALL_PACKAGES`).

## Device

- TV serial lives in `/tmp/tvserial` (`[IP_ADDRESS]:5555`); adb over Wi-Fi drops often —
  `adb connect "$(cat /tmp/tvserial)"` before each batch.
- Deploy `install -r` to keep state; `uninstall` wipes prefs (dock pins, theme, hidden apps).
- Long-press cannot be injected with `input keyevent --longpress`; use
  `adb shell input swipe X Y X Y 900`. D-pad = `input keyevent KEYCODE_DPAD_*`.
- Screenshots: `adb exec-out screencap -p` (1920×1080 at density 320 ⇒ 960×540 dp).
- **This app is not the TV's default home** (Projectivity `com.spocky.projengmenu` is). Never run
  `cmd package set-home-activity` or role changes without explicit user approval. Because of that,
  BACK/force-stop dump you into Projectivity — relaunch via
  `am start -n com.mdev64.simpletvlauncher/.MainActivity`.
- Projectivity's "TV Apps" row currently shows our **`android:banner`** (`tv_banner.png`); older
  notes said it drew `android:icon` letterboxed, so keep both reading well. After the 2026-11
  icon change the new banner appeared right after `install -r` — no cache flush was needed. If
  stale art ever sticks, `adb shell pm trim-caches 9999G` (clears every app's cache — ask the
  user first) + `am force-stop com.spocky.projengmenu`, then relaunch home.
- Wallpaper picker = the launcher's own **`ImagePickerOverlay`** (in-activity, D-pad
  driven, reuses `item_tile` + `TileStyler`): MediaStore image folders → photos, opens in the
  last-used folder (`Prefs.wallpaperBucket`) or straight into the only folder, focuses the
  current wallpaper; BACK = photos → folders → close (routed first in
  `SettingsActivity.dispatchKeyEvent`). Needs READ_MEDIA_IMAGES (READ_EXTERNAL_STORAGE ≤ 32),
  asked on first use. Why not the system pickers: on Android TV `OPEN_DOCUMENT` is a
  "not supported" stub (this TV only works because File Manager+ is installed, and it ignores
  `EXTRA_INITIAL_URI`), and the photo picker (`ACTION_PICK_IMAGES`) can't move focus onto a
  photo with a remote.
  The first folder is always **"Built-in"**: `res/raw/wallpaper_builtin_1..5.webp`
  (1600×900, WebP q72, from the user's own Unsplash/Pexels wallpapers — free licences;
  credits in `ImagePickerOverlay.builtIn()`), stored as `android.resource://pkg/raw/<name>`
  (by **name** — numeric ids change between builds) so `WallpaperStore` reads them like any
  photo. Without photo permission only Built-in shows, and the footer says how to grant it;
  with it, the footer explains how to copy photos onto the TV. No system-picker fallback
  any more. The pick is converted into `filesDir` at once.
- The user drives the TV while we work: before scripted key presses check `mCurrentFocus`
  and stop if it isn't ours — stray keys have opened other apps. Settings isn't exported, so
  reach it via the top-bar launcher-settings button.
- Testing pin counts without losing the user's pins: force-stop, `run-as … cat
  shared_prefs/tvlauncher.xml` to back up, edit the `pins` string, push via /data/local/tmp +
  `run-as … sh -c 'cat … > shared_prefs/tvlauncher.xml'`, relaunch; restore the backup after
  and `diff` it.
- Don't `force-stop` the default home: the next HOME press lands in an unrelated app. Restart it
  with `am start -a android.intent.action.MAIN -c android.intent.category.HOME`.

## Architecture worth knowing

- Home is a **carousel** (`activity_main.xml`, this branch): top bar (clock left; `top_group`
  plain row — no glass — of Wi-Fi / VPN / launcher / TV-settings buttons (no All apps
  button: DOWN opens All apps) +
  `top_status` line showing the focused button's value), `CarouselView` (added into
  `carousel_host` in code), page dots (`app_caption` "4 of 20" replaces them past
  `MAX_DOTS`), and `home_footer` (OK Open · ALL APPS ⌄ · HOLD OK Options;
  `HomeKeyChip`/`HomeHint` styles). Hints + dots dim to 35 % when focus leaves the carousel
  (`updateCarouselCues`). `all_apps_page` is a full-screen layer over `home_layer`; while
  open, `home_layer` is `FOCUS_BLOCK_DESCENDANTS`. Open/close is a sweep (`SWEEP_MS`): the
  page rises from the bottom while `home_layer` travels 35 % up and fades (then goes
  INVISIBLE under the opaque page); close reverses it. Always close via `closeAllApps()` —
  it restores `home_layer`'s translation/alpha/visibility. There is no glass on the home screen.
- Home grid lines (dp, 960×540): side margins 48 (clock left edge = footer start; status /
  last icon glyph = footer end). Top bar padding 14 with the clock column +14 (starts at
  28, TV-safe) so the icon glyphs (row starts at 14) also start at 28 — level with the
  greeting; `top_status` sits 4dp under the icons. Card 128–452, dots at 470, footer bottom
  27dp (TV-safe area — never put content in the bottom 27dp).
- `CarouselView` centre card (576×324dp): `FrostDrawable` (bottom of `CarouselView.kt`) —
  ONE rounded fill whose `ComposeShader` stacks a blurred wallpaper backdrop, a translucent
  tint in the app's palette (`paletteOf`: most vivid colour of a 12×7 sample of its art,
  `shade()`-darkened, milky sheen folded into the top stop) and static grain, plus a 1dp
  rim. The backdrop is built once per wallpaper in `MainActivity.updateCardFrost()`: the
  wallpaper patch behind `centerCardOnScreen()` (mapped through bg_image's centerCrop),
  halved with bilinear scaling down to ~72px; it needs `BitmapShader.FILTER_MODE_LINEAR`
  (API 33+) or it renders as blocks. Null for solid backgrounds. Don't swap this for
  `MatteSurfaceDrawable` (five fills ≈ +6 ms/frame). Then a 256×144dp poster whose art is a
  `RoundedArt` drawable (bitmap-shader rounded rect — **no** `clipToOutline`), name +
  `Listener.describe()` (category · System app · version), content centred vertically.
  Neighbours are placeholder tiles at `SIDE_SCALE` 0.8 (80 % of the card), cut off by the
  screen edge: palette colour + initial + name, text shifted into the visible part. Each
  recycled card holds both faces; `peek` (0 = card, 1 = tile) cross-fades them only between
  `SWAP_FROM`..`SWAP_TO` (0.42–0.58) so one big face is drawn most of the time. Tile text
  sizes/radius are pre-divided by `SIDE_SCALE`.
- **Edge fade belongs to the screen, not the cards.** `edge_fade_left/right` (220dp, over
  `carousel_host`, under the top bar/footer) are static smoothstep gradients in the edge
  colour (`MainActivity.applyEdgeFade`: `@color/bg` for photos, else the solid colour). They
  only cover the card band (`edge_band_top`/`_height`, 128–452dp); above/below it the same
  gradients are baked into the cached photo scrim (LayerDrawable over `bg_scrim.xml`), so
  the darkening is seamless. Per-card masks were tried and rejected: a fade that moves with
  its card lets sharp card edges slide through the screen edge (visible "lines" mid-step),
  and `saveLayer` masks cost 10–20 ms/frame while cards move. `bg_scrim.xml` also has 340dp
  dark side vignettes so bright photos don't glow at the edges.
- **Performance (Mali GPU, API 34, fill-rate bound — the UI thread mostly waits on
  `queueBuffer`):** stepping is ~26 ms/frame. Measure with `dumpsys gfxinfo <pkg> reset`,
  RIGHT ×4 at 0.8 s, read the percentiles, and **A/B against the previous commit on the same
  wallpaper** (sharp vs blurred photos differ by ~5 ms). Check motion with `screenrecord` +
  `ffmpeg -vf mpdecimate` — `screencap` is too slow to catch a 300 ms step. What keeps it
  there: `window.setBackgroundDrawable(null)`; root bg cleared under an opaque photo;
  `bg_layer` (photo + scrim) is a hardware layer = one cached texture per frame. Don't add:
  `clipToOutline` on anything that moves (+~10 ms), full-screen gradients that change per
  frame (the old ambient glow, ~25 ms), offscreen layers on moving cards, or
  `LAYER_TYPE_SOFTWARE`. Text drawn with a paint shader must not be `singleLine` (its wide
  scrolling layout shifts the canvas and throws the shader off) — use `maxLines = 1`.
- 5 cards are recycled at offsets -2..+2 (±2 sit off screen so steps slide in) and wrap
  forever; draw order is by distance from centre via `getChildDrawingOrder` (no translationZ →
  no TV shadows). It holds focus itself; OK/long-press come from the stock click/long-click
  handling, LEFT/RIGHT from its `onKeyDown`. Art is requested at `artWidth x artHeight` (the
  poster size).
- Focus: carousel UP → top bar (`lastTopIndex`), DOWN → All apps; top bar DOWN/BACK → carousel.
  `onResume` puts focus back on the carousel (returning from TV/launcher settings), and the
  VPN menu sets `AppMenuOverlay.returnFocus = carousel` so Cancel/BACK land there too.
  On the All apps page UP from row 0 or BACK closes it; LEFT/RIGHT never wrap rows.
- Pins are unlimited (`DockAdapter.DOCK_SIZE = Int.MAX_VALUE`) and are the carousel order.
  The full-screen grid is labelled **"Other apps"** (ids/code still say `all_apps`): it lists
  visible apps that are **not** pinned, opens focused on the first tile (one UP = home), and
  its hold menu offers "Add to home" (the tile then leaves the grid; focus stays put, or the
  page closes when it empties). With every app pinned, DOWN just shows a toast.
- Short lists (`CarouselView.itemAtOffset`): 0 pins → frosted empty card, footer hint becomes
  "OK All apps" and the Options hint hides; 1 pin → no neighbours, LEFT/RIGHT do nothing;
  2 pins → the other app shows on **both** sides (user's call: LEFT and RIGHT both go to it).
  `step()` rebinds a card that moves into a spot showing a different app, but never one
  heading for a hidden spot (it keeps its content while fading out).
- All apps grid: 5 columns (`HomeAdapter.SPAN = 60`, `appColumns`), clipped to padding with a
  vertical fading edge. `tile_margin_h` (14dp) must stay above the ~11dp focus growth or the
  edge columns clip; `computeTileWidths()` must match the grid's 16dp side padding.
- `TileStyler.attach(tile, accent, focusScale)` is called per bind and guarded by
  `appliedAccent`; changing the accent therefore requires the `notifyDataSetChanged()` that
  `MainActivity.applyTheme()` performs. The accent focus pill is drawn on the tile's **parent**
  (`item_tile` keeps 10dp bottom padding for it).
- `AppMenuOverlay` is a hand-built in-activity overlay, not a `Dialog`: stock dialog windows
  composite see-through on this TV. Its card is deliberately opaque (tint forced solid and
  darkened) — the translucent glass made the menu text unreadable over tiles. Rows need `isFocusableInTouchMode = true` plus explicit
  `nextFocusUpId/DownId` chaining or the D-pad escapes behind the scrim. Settings confirmations
  reuse it.
- Clock is a `TextView` driven by a 15 s `Handler` ticker; `TextClock` never renders on this TV.
- `ArtworkLoader` cache key must stay `"$w x $h ${item.key}"` (10 MB byte-bounded LRU — carousel
  posters are ~290 KB each). Artwork preference: banner → logo → icon; wide art fills the tile,
  square icons are drawn at 64 % height.
- `Prefs` (SharedPreferences `tvlauncher`): pins are an ordered `|`-separated list of
  `ComponentName.flattenToShortString()` (uncapped on this branch). `AppItem.key`
  uses the same string and is the identity for pins/hidden entries.
- Theming: `Prefs.accent` drives the greeting, header-button fill, All-apps tile focus pills,
  page dots, chevron and settings selection. Clock stays white on purpose. Focus = fill/lightness
  change; borders only mark "selected".
- Glass surfaces are `GlassLayout` (background = `MatteSurfaceDrawable`). Do **not** reimplement
  them as layer-list drawables, and do not put a `match_parent` glass child inside a
  `wrap_content` parent. On this branch they are only used by Settings and `AppMenuOverlay`
  (the home stage/top-bar glass and the `pinGlass` pref were removed).
- Header buttons use a stateful `imageTintList` (accent idle, contrast-safe glyph on the accent
  fill). Never also call `setColorFilter` on them — the filter wins and the glyph becomes
  accent-on-accent (invisible) while focused.
- Menu/settings strings are inline Kotlin literals; only manifest labels and content
  descriptions live in `strings.xml`.
- App icon artwork is generated by **`tools/make_icon.py`** (Pillow; `--preview` writes
  `tools/icon_preview.png`, don't commit it): the mark is the launcher itself — violet→blue
  centre card with a glow, two faint frosted side cards fading out, the page-dot row. It
  writes the transparent adaptive foreground (`drawable-nodpi/ic_launcher_foreground.png`,
  432², also the boot-splash logo), five legacy `mipmap-*/ic_launcher.png` (plate + mark at
  92 %) and `drawable-nodpi/tv_banner.png` (320×180, mark + "Simple TV / LAUNCHER" in Avenir
  Next from macOS). Adaptive XML: `@color/ic_launcher_bg` (#1B1B25, = the script's `PLATE`)
  behind a 6 % inset — enough for the card corners to clear a circle mask. No
  `<monochrome>` layer. Edit the script, rerun it, clean-build, reinstall.

## Repo hazards

- **No git repo and no backups.** A previous scripted replace (empty/`-1` slice) deleted
  `MainActivity.kt`. Prefer the `edit` tool; if scripting, assert the match exists and is
  non-empty before writing.
