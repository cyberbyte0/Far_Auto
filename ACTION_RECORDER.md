# Action Recorder & Live Inspector — Implementation Document

Status: **Proposal / design spec** (not yet implemented)
Owner: TBD
Related code: `FarAutoAccessibilityService.kt`, `AutomatorBridge.kt`, `DashboardServer.kt`, `app/src/main/python/automator.py`, `ScriptExecutionService.kt`

---

## 1. Goal

Let the user **generate scripts by interacting with the target app** instead of manually
inspecting elements with `ui_explorer.py`. Two related capabilities:

- **Live Inspector** — arm a mode, tap any on-screen element, and instantly see its
  selectors (resource-id, text, content-desc, class, bounds) plus a ready-to-paste
  `automator.*` snippet.
- **Action Recorder** — record a session of real taps / long-presses / text entry /
  scrolls and emit a runnable Python script that reproduces them.

Both reuse the existing `FarAutoAccessibilityService`; neither requires new runtime
permissions.

---

## 2. Why this is feasible today

`app/src/main/res/xml/accessibility_service_config.xml` is **already** configured for it:

```
android:accessibilityEventTypes="typeAllMask"          # we receive clicks, text, scroll, window changes
android:accessibilityFlags="... flagReportViewIds ..." # resource-ids are exposed on nodes
android:canRetrieveWindowContent="true"                # node tree access
android:canPerformGestures="true"                      # replay (already used)
```

`FarAutoAccessibilityService.onAccessibilityEvent()` already exists (used for toast
capture). The recorder is **added logic on an existing event pipeline**, not new plumbing.

---

## 3. How action capture works

Android fires an `AccessibilityEvent` for each interaction. The recorder cares about a
small, low-frequency subset (these only fire on actual user actions):

| Event type | Captured from `event.source` | Emitted step |
|---|---|---|
| `TYPE_VIEW_CLICKED` | resource-id, text, content-desc, class, bounds, package | `click` |
| `TYPE_VIEW_LONG_CLICKED` | same | `long_click` (future API) |
| `TYPE_VIEW_TEXT_CHANGED` | field identity + committed text | `input_text` |
| `TYPE_VIEW_SCROLLED` | scroll direction / delta, container bounds | `swipe` |
| `TYPE_WINDOW_STATE_CHANGED` | new package / activity | insert `wait_for_element` / `wait` |

**Explicitly ignored:** `TYPE_WINDOW_CONTENT_CHANGED` (fires dozens/sec — never fetch the
source node for it), and all other noise. This filtering is the single most important
performance decision (see §9).

---

## 4. Selector strategy (the quality of generated scripts)

For each captured node, pick the most stable locator using a preference ladder. The whole
point of an event-based recorder (vs. coordinate recording) is that we get the **node**,
so scripts are selector-based and survive screen-size / layout changes.

1. **resource-id** (`viewIdResourceName`, e.g. `com.app:id/login_btn`) — best.
2. **content-description** — good for icons/ImageButtons.
3. **visible text** — good for buttons/labels (note: locale-dependent).
4. **class name + index** among siblings — weak but works.
5. **bounds center coordinate** — last resort; brittle, screen-size dependent.

Capture **2–3 candidates** per step when available so replay can fall back if the primary
selector disappears. Record `package` too, to scope `wait_for_element`.

---

## 5. Step data model

A recording is an ordered list of steps. In-memory only during capture; serialized to
Python on stop.

```
Step {
  action: "click" | "long_click" | "input_text" | "swipe" | "wait_for_element" | "wait"
  selectors: [ {by: "id"|"desc"|"text"|"class", value: String}, ... ]   // ranked
  bounds: Rect?                  // for coordinate fallback / swipe synthesis
  text: String?                  // for input_text
  scrollDir: "up"|"down"|...     // for swipe
  package: String?
  tMillis: Long                  // relative timestamp, for optional delays
}
```

---

## 6. Script generation

Translate steps → Python on **Stop** (not during capture). Example output:

```python
import automator

automator.wait_for_element(resource_id="com.app:id/search", timeout=5)
automator.click(resource_id="com.app:id/search")
automator.input_text("hello world")
automator.click(text="Search")
automator.wait_for_element(text="Results", timeout=5)
automator.swipe_up()
```

Generation rules:
- After a `WINDOW_STATE_CHANGED`, insert a `wait_for_element` on the next step's selector
  (makes replay reliable instead of racing the UI).
- Coalesce consecutive `TEXT_CHANGED` on the same field into **one** `input_text` (commit
  on focus-change / next action).
- If a step only has a coordinate fallback, emit `automator.click(x, y)` **and** a comment
  warning that it is coordinate-based.
- Header comment with capture date, target package, step count.

> **API note:** `click`/`input_text`/`swipe`/`wait_for_element` exist in
> `automator.py`. `long_click` does not yet — either add it to the public API
> (`AutomatorBridge` + `automator.py`) or fall back to `click` during phase 1.

---

## 7. UX / surfaces

### Dashboard (`dashboard.html` + `DashboardServer.kt`)
- **Record** / **Stop** toggle and an **Inspect** toggle in the header.
- Live step list while recording (poll a new `/recording_steps` endpoint, mirroring the
  log-poll pattern), with per-step delete.
- **Generate Script** → shows the Python, lets the user name & save it (reuse
  `/save_script`).
- New endpoints (token-authed, same rules as every route):
  `/record/start`, `/record/stop`, `/record/steps`, `/inspect/last`.

### Native app
- A floating bubble (overlay) with Record / Inspect controls, or a toggle in `MainActivity`.
- Inspector: tapping an element shows a bottom-sheet with selectors + a "copy snippet" /
  "insert into editor" action.

---

## 8. Phased plan

**Phase 1 — Live Inspector (lowest risk, ships value fast).**
Arm inspect mode; on `TYPE_VIEW_CLICKED` capture the source node, extract the selector
ladder, expose via `/inspect/last` and an in-app sheet. No script generation yet. This
builds and proves the selector-extraction code that the recorder needs.

**Phase 2 — Action Recorder (semantic).**
Add the `AtomicBoolean recording` flag, the step buffer, event filtering/coalescing, and
script generation. Dashboard Record/Stop + live steps + save.

**Phase 3 — Hybrid coordinate fallback.**
When a tapped point yields no usable node, record the bounds-center / coordinate so
canvas / game / WebView taps are still captured (with brittleness warnings).

**Phase 4 (optional) — Raw-gesture overlay recorder.**
Transparent overlay capturing raw touch paths for apps with no accessibility nodes.
Requires `SYSTEM_ALERT_WINDOW`, adds input latency, and must re-dispatch touches — only
build if Phase 3 proves insufficient.

---

## 9. Performance & device impact (esp. low-end)

The accessibility callback runs on the **app main thread**, which sits in the device-wide
input path. Heavy work there jank-s the *whole device*. Rules to keep impact negligible:

1. **Idle cost is zero.** Guard `onAccessibilityEvent` with the `recording`/`inspecting`
   `AtomicBoolean`; return immediately when off. (Events already arrive today because the
   config is `typeAllMask` — we are not adding event traffic, only optional processing.)
2. **Never fetch `event.source` for `WINDOW_CONTENT_CHANGED`.** Only fetch it for the few
   user-action event types in §3 (human-frequency, a few/sec).
3. **Buffer in memory; serialize on Stop.** No file I/O or script generation on the event
   thread (same discipline as the existing `logBuffer`).
4. **Keep per-event work to string reads.** `getBoundsInScreen`, `viewIdResourceName`,
   `text`, `contentDescription` are cheap; do not walk the whole tree per event.
5. **Recycle nodes** and avoid retaining `AccessibilityNodeInfo` references.
6. **Don't record and run a script at the same time.** The `AutomatorBridge` posts to the
   main looper with a 5s timeout; a busy main thread could time gestures out.

With these rules, even a low-end device sees no meaningful slowdown: idle = a boolean
check, active = light work on occasional user taps. The *only* way to make it stutter is
to violate rules 2–4 (process the content-changed flood / do I/O on the event thread).

---

## 10. Limitations (be honest in the UI)

- **Apps without accessibility nodes** — Jetpack Compose (older versions), WebViews,
  games, custom-canvas UIs may fire clicks with no resource-id or no `source` at all.
  Recorder degrades to coordinates (Phase 3) or cannot capture.
- **`FLAG_SECURE` windows** (banking/DRM) expose no node — same blind spot as
  `automator.is_secure_window()`. Recording is impossible there.
- **Gestures are lossy** — `SCROLLED` gives direction/amount, not the exact finger path;
  swipes are synthesized.
- **Selector drift** — some apps omit/reuse resource-ids; multi-candidate selectors help.
- **Text capture is per-keystroke** — must be debounced/coalesced per field.

---

## 11. Privacy & safety

- Recording can capture **typed text**, including potentially sensitive input. Password
  fields are usually `FLAG_SECURE` / non-reporting, but **mask or skip** `input_text` when
  the field is a password class, and show a recording banner.
- Recording is **off by default**, explicit user toggle, with a persistent visual
  indicator while active (reuse the REC-pill pattern in the dashboard header).
- Captured steps stay on-device (in-memory → saved script), never transmitted beyond the
  existing token-authed local dashboard.

---

## 12. Non-goals

- Cross-device "smart" selector healing / ML element matching.
- Recording inside `FLAG_SECURE` apps.
- Pixel-perfect gesture path reproduction.

---

## 13. Open questions

- Add `long_click` to the public `automator` API now, or defer (Phase-1 fallback to click)?
- Inspector surface in-app: floating bubble (needs overlay permission) vs. an in-activity
  armed mode?
- Should generated scripts default to selector-only and *warn* on coordinate fallback, or
  silently include coordinates?
- Step-editing UX before save (reorder/delete/insert waits) — dashboard only, or app too?
