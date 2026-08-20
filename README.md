# ApiScope

**English** · [中文](README.zh-CN.md)

> **A lightweight API client that lives inside your IDE.** Fire a request straight from a Spring `@Controller` method — method, path and a fully-expanded JSON body are filled in from the source for you.

Community Edition friendly — it ships its own request engine and does **not** depend on IntelliJ Ultimate's HTTP Client.

## Background — why build another one?

Most API tools have grown into full **platforms**. Postman, Apifox and Insomnia now bundle accounts, cloud sync, mock servers, team workspaces and scripting engines — and the IDE plugins have followed the same path, adding environment collections, service discovery and request trees, getting heavier every release. A genuinely **lightweight** HTTP client — one you install and use in seconds, with no login, no clutter, no concepts to learn — has become surprisingly rare.

ApiScope is a deliberate step back to that. It does the one thing an IDE can *uniquely* do — **fill the request from your source code** — and refuses to grow into a platform. No account, no cloud, no mock, no orchestration; just a request you can send.

## What it is

ApiScope is **not** an API platform. It does exactly one thing that only an IDE plugin can do: **read your source and pre-fill the request**.

- Click the ➜ gutter icon next to a Controller method and the panel opens with the method, path, and the request DTO **recursively expanded into a full-field JSON body** — including superclass fields, generic substitution, `@JsonProperty` renames, and `@JsonIgnore` skips.
- `@RequestParam` types and the method's javadoc `@param` descriptions land in the Params table; optional params start unchecked.
- `@RequestHeader` / `@CookieValue` are added to the Headers / Cookie tabs (**added, never overwritten** — your hand-written auth headers survive).
- Un-annotated query objects (`list(OrderQueryDTO q)`) are flattened into query params.
- Unresolvable path placeholders like `{id}` are **kept and block sending**, instead of being silently replaced with an empty string that produces a "looks complete" but wrong URL.

## Why "lightweight"

- **One tool window is the whole thing.** No login, no account, no cloud sync, no Mock, no test orchestration.
- **The `core` layer has zero third-party dependencies.** Response prettifying is a hand-written single-pass scanner — no Jackson/Gson, so nothing clashes with the platform's bundled versions.
- **The request engine is the JDK's own `HttpClient`,** which is why it works on Community Edition.
- **No config files.** Install and use; changes on the panel take effect immediately. State lives in the project's `.idea/workspace.xml` (never committed).

## How it compares — and where it falls short

ApiScope is deliberately narrow. If you need a full API platform, keep Postman/Apifox. Even other *in-IDE* REST plugins tend to reach for environment collections, service discovery and request trees — ApiScope skips all of it on purpose (it tried service discovery + gateway-prefix guessing once; debugging a wrongly-guessed address cost more than typing the address once, so the whole thing was removed). This table is honest about what ApiScope **cannot** do.

| Dimension | **ApiScope** | Postman / Apifox | IDEA HTTP Client (`.http`) | Other IDE REST plugins |
|---|---|---|---|---|
| Where it runs | In-IDE tool window | Standalone app / web | In-IDE (**Ultimate only**) | In-IDE |
| Source-aware body from Controller | ✅ **its whole reason to exist** | ❌ manual or import from docs | ❌ manual | ~ varies, usually partial |
| Works on IntelliJ **Community** | ✅ (own engine) | n/a (external) | ❌ | ~ varies |
| Team collaboration / sharing | ❌ none | ✅ workspaces, comments | ~ via git (files) | ~ varies |
| Cloud sync / account | ❌ none, all local | ✅ | ❌ local files | ~ varies |
| Environments & collections | ❌ **none** (only `{{vars}}` + history) | ✅ rich | ✅ env files, collections | ✅ usually |
| Multiple tabs / request tree | ❌ **one request at a time** | ✅ | ✅ (files) | ✅ usually |
| Protocols | **HTTP/HTTPS only** | HTTP, GraphQL, gRPC, WebSocket, SSE… | HTTP, some GraphQL/WebSocket | ~ varies |
| Pre/post-request scripting | ❌ none | ✅ JS sandbox | ~ limited | ~ varies |
| Automated tests / CI runner | ❌ none | ✅ (Newman/CLI) | ~ response handlers | ~ varies |
| Mock server | ❌ none | ✅ | ❌ | ~ varies |
| Auth helpers (OAuth2 flows, AWS Sig) | ❌ paste tokens by hand | ✅ built-in flows | ~ limited | ~ varies |
| Language scanned for endpoints | **Java only** | n/a | n/a | ~ varies (some do Kotlin) |

**Where ApiScope is clearly worse (the honest disadvantages):**

1. **No team collaboration or cloud sync.** It is a single-developer, single-machine tool. There is no way to share a collection with your team, review requests, or sync across machines — Postman/Apifox exist for exactly this.
2. **No environments or collections management.** You get `{{variables}}` plus a 20-item history — that's it. Organizing hundreds of endpoints across many services is far weaker than a real API platform.
3. **One request at a time, no tabs.** Comparing two endpoints side by side, or keeping a suite of requests open, is not possible.
4. **Java-only source scanning.** Kotlin Controllers get no gutter icon and no auto-fill — which means the plugin's *entire* advantage disappears on a Kotlin codebase. You can still type URLs by hand, but then a standalone tool serves you better.
5. **HTTP/HTTPS only.** No first-class GraphQL, gRPC, WebSocket, SSE, or Socket.IO UI. If your API isn't plain HTTP, use Postman/Insomnia.
6. **No scripting, no assertions, no CI.** You cannot build automated regression suites or chain requests with logic. Postman scripts and IDEA's `.http` response handlers can; ApiScope can't.
7. **No mock server** and **no built-in auth flows** — you paste bearer tokens into a header or a variable yourself.
8. **Cookie handling is intentionally simplified:** host-suffix matching only, and it **ignores expiry / `Secure`**. Over a long session it may resend a stale cookie (mitigated by a manual "clear cookies" action).
9. **Large responses aren't prettified.** Bodies over 512 KB are shown raw (no highlight/fold) to keep the editor responsive.
10. **cURL import only recognizes common flags** — anything else is reported, not guessed.
11. **Tied to JetBrains IDEs.** It's useless outside IntelliJ, and it shines only when you have the API's *source open*. For pure black-box exploration of a third-party API you don't own, a standalone client is the right tool.

In short: ApiScope trades breadth for a single sharp edge — turning source you already have into a ready-to-send request in one click. Everything above is the price of staying that thin. See [Known limitations](#known-limitations) for the finer edges.

## Features

| Capability | Notes |
|---|---|
| One-click from a Controller gutter icon | Fills method / path / full-field JSON body, ready to send |
| DTO → **full-field** JSON body | Superclass fields, generics, enums, dates, `@JsonProperty` rename, `@JsonIgnore` skip |
| Param **type & description read from source** | `@RequestParam` type + javadoc `@param`; optional params unchecked |
| `@RequestHeader` / `@CookieValue` read too | Added to Headers / Cookie tabs (**add-only, never overwrite**) |
| Un-annotated params read | `list(OrderQueryDTO q)` flattened to query; framework params (`HttpServletRequest`) skipped |
| Base URL candidate from `application.yml` | Reads the module's `server.port` / `context-path` as one dropdown suggestion (you still give the address) |
| Key-value tabs + "Bulk edit" | Params / Headers / Cookie / Variables / Form fields; switch to plain text to paste in bulk |
| 6 body formats | none / JSON / XML / text / form-urlencoded / multipart (with file upload) |
| `{{variable}}` interpolation | In URL, Headers, and Body; substituted right before sending |
| Global Headers | Auto-appended to every request (auth tokens etc.); a same-named header in the request wins |
| Request history | Last 20, one-click refill |
| Response in a platform Editor | JSON highlight, fold, line numbers, `Ctrl+F`, soft-wrap, invalid-JSON red squiggles |
| Copy as cURL / import from clipboard | Exports the real interpolated request; import reports unsupported flags instead of dropping them silently |
| Auto-carry cookies | Response `Set-Cookie` stored per host, sent automatically on the next same-host request |

## Install

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
./gradlew :plugin:buildPlugin
# artifact: plugin/build/distributions/ApiScope-0.3.1.zip
```

IDEA → Settings → Plugins → ⚙ → **Install Plugin from Disk** → pick the zip → restart.
Or install from the [JetBrains Marketplace](https://plugins.jetbrains.com/) (search "ApiScope").

Debug run (spins up a sandbox IDE with the plugin, without touching your main IDE):

```bash
./gradlew :plugin:runIde
```

Run tests:

```bash
./gradlew :core:test    # pure logic, seconds
./gradlew :plugin:test  # real PSI environment, boots a light IDE fixture
```

## Usage

1. Open any `@RestController` / `@Controller`; a ➜ icon appears in the gutter next to each method.
   (Keyboard: put the caret in a method and press `⌥⌘A` / `Ctrl+Alt+Shift+A`, or find *Open Endpoint in ApiScope* via `Cmd+Shift+A`.)
2. Click it → the `ApiScope` tool window opens on the right with method / path / full-field JSON body pre-filled.
3. Set the Base URL (e.g. `http://localhost:8080`; a candidate is offered from the module's `application.yml`), then **Send**.
4. Need auth? Add `Authorization: Bearer xxx` on the **Global Headers** tab — it's attached to every request afterwards.

Not starting from source? Just paste a full `http://...` URL into the path box (anything starting with `http://` is taken as-is).

## Known limitations

- Java source only (`language="JAVA"`); Kotlin Controllers aren't recognized yet.
- One request at a time (no tabs).
- One set of Global Headers; switch environments via a `token = ...` line on the Variables tab.
- Read timeout defaults to 300s (15s would cut the connection while you're stepping through a breakpoint); tune it in the title-bar gear menu. A 3s **connect** timeout catches typo'd addresses.
- The address bar and tables **preserve exactly what you typed**; percent-encoding is applied only at the last step before sending. Separators `&` / `=` are left alone, so encode them yourself if a value truly needs them.
- Cookies ignore expiry and `Secure`, and match by host suffix only.
- Responses over 512 KB are not prettified or highlighted.
- cURL import recognizes common flags only; the rest are reported, not guessed.

## Architecture

See the [Chinese README](README.zh-CN.md#架构) for the full architecture notes and design rationale. In short:

- `core/` — no dependency on the IntelliJ API and **no third-party libraries**; compiles and tests standalone (`gradle :core:test`). Contains the JDK-`HttpClient` request engine, body encoding, cookie parsing, key-value ⇄ table model, JSON sample/printer, `{{variable}}` interpolation, and cURL generate/parse.
- `plugin/` — the IntelliJ layer: Controller scanning, DTO → full-field JSON, mapping-annotation & javadoc reading, the gutter icon, the request panel UI, and state persistence via `PersistentStateComponent`.

## License

[MIT](LICENSE) © Red

Icons (`plugin/src/main/resources/icons/`, `META-INF/pluginIcon.svg`) are an original geometric bull's head, distributed under this repository's MIT license.
