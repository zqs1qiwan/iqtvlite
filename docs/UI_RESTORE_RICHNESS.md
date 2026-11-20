# UI RESTORE RICHNESS (t81)

2026-11-20. Task t81 (developer2). User feedback: only the **blue focus box** was unwanted — the layout/logo richness was fine, but the earlier "de-clutter" pass had flattened the drawer to plain text. This restores richness per `docs/UI_VISUAL_SPEC_TIVIMATE.md` **v3**, while keeping the **no-blue-border** focus. On top of t77.

## Channel rows (300 × 64)
- 2dp accent bar + **40×40 台标 Logo** (2dp tile) + **two-line text**: channel name **16sp / medium** (focused bold via weight selector) and current-program subtitle **12sp / `#C7D0DD`**; `▶` marker prefixed for the playing row (preformatted), number kept in the name.
- 64dp fixed height; square row; focus = `#FFFFFF14` background + text colour/weight selector, **no border/outline/glow**.

## Program rows (300 × 52)
- 2dp accent bar + **20×20 台标** + **time column** (`HH:mm`, 11sp `#A9B4C7`) + program name **15sp**; NOW via amber accent (`state_activated`), playing via blue accent (`state_selected`). Square rows.

## Group / Date rows
- Groups 40dp, dates 44dp, text-only (as before), same selector focus.

## Logo loading (no main-thread block, no column rebind)
- Reuses **`LogoLoader`** (memory LRU 96 + disk cache): `getInMemory(url, w, h)` for an instant hit, else a **2dp rounded placeholder** then an async `LogoLoader.load(appCtx, url, w, h)` coroutine (loads on Default/IO internally) sets the bitmap only if the holder still shows the same url.
- Decode targets: channel **80×40px**, program **40×20px**. ImageView has a fixed dp size, so logo arrival does **not** re-layout the row.
- **Only visible rows** bind (RecyclerView recycling); `onViewRecycled` cancels the pending logo job (`holder.logoJob?.cancel()`) — no leaked work, no rebind.
- Focus movement never calls `notifyItemChanged`/rebind; logos are bound in `onBindViewHolder` only.

## Focus (unchanged from t77)
- No blue border. Row background `StateListDrawable` (`state_focused` → `#FFFFFF14`); primary text `ColorStateList` (`state_focused` → `#F2F5FA`, else `#A9B4C7`); `FocusWeightTextView` switches medium→bold on focus via `drawableStateChanged` (no adapter rebind). Secondary stays `#C7D0DD` (channel) / `#A9B4C7` (time).

## Info banner
- The Compose `ChannelInfoBanner` still uses `LogoLoader` for the channel logo + channel name/program/time — verified not removed in t50/t62 (unchanged; remains in the Compose overlay).

## Radii / shadows
- Rows 0dp; logo tile 2dp; date chip 2dp (row); panel container 2–4dp; **no large shadow/blur/hard frame** (per v3).

## Preserved
633dp base + groups 151 / dates 120 overlay columns; selector focus (no rebind); DiffUtil; EPG out of `SidebarState` equality; transparent root; catchup owner B; layered dates; zero-copy SurfaceView base / grey-screen fix / channel keys / LoadControl / stable keys / SR / TIV / JNI / R8 / UA. Size ≤30MB.

## Verification
- Local `--no-daemon` `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; JVM **37 tests 0 failures**; lint **0 errors**/72 warnings; pair gate missing 0; keep 210 roots identical; dex contract 7 OK.
- CI receipt (run87, 44684317240): source commit [84f2bc8b4f5ed0082c78652f4aa958dabe068824](https://github.com/zqs1qiwan/iqtvlite/commit/84f2bc8b4f5ed0082c78652f4aa958dabe068824), [run 44684317240](https://github.com/zqs1qiwan/iqtvlite/actions/runs/44684317240) **successful** (NDK r27, dual ABI/API24, pair gate passed). Cloud JVM 37 tests 0 failures; lint 0 errors / 70 warnings / 5 hints.

| Deliverable | Actual bytes | SHA256 |
|---|---:|---|
| app-debug.apk |10,208,256| `add07266c1b8b0deaa9e8a9bec4b14ca6201b549cd5d14ed3abb3c6bd6a77339` |
| app-debug-androidTest.apk |502,955| `80d2276693e58fe95039f332aea8a397a0af297e816e9cb05c294033680be657` |

- Artifacts: pair `14606370637` (`sha256:d3ae6d6a916ddcb96516ab289e72d77fcd5fabbefe4d5bdfbcf94e1510026925`, 8,699,773B); reports `14606016282` (`sha256:b66548803d07efa25b5e6006cda9724c74bade518a713c02454980c173271214`). Both APKs signer `29473c96813fe35e0d42219841cd6492216740a318ad14b67d9e02edab16c339` (fresh debug cert).
- Version: `tv.iqtvlite.app.validation / 10087 / 2.2.0-tivimini-84f2bc8b-validation`; label "IQTV Lite 验证版"; min24/target35/compile36; dual ABI; contract missing types/methods/fields empty; keep 210 roots identical.
- Size 10,208,256B ≤ 30MB. Device verification is acceptance2's (t83) on this exact pair; this does not claim the jank target is met.
