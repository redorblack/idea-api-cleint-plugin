# ApiScope

> **A lightweight API client that lives inside your IDE.** Fire a request straight from a Spring `@Controller` method — method, path and a fully-expanded JSON body are filled in from the source for you.
>
> **长在 IDE 里的极简 API 客户端。** 在 Spring `@Controller` 方法旁点一下,method、路径、以及入参 DTO 递归展开成的**全字段 JSON body** 就从源码填好了。

**[English](#english)** · **[中文](#中文)**

Community Edition friendly — it ships its own request engine and does **not** depend on IntelliJ Ultimate's HTTP Client.

---

## English

### Background — why build another one?

Most API tools have grown into full **platforms**. Postman, Apifox and Insomnia now bundle accounts, cloud sync, mock servers, team workspaces and scripting engines — and the IDE plugins have followed the same path, adding environment collections, service discovery and request trees, getting heavier every release. A genuinely **lightweight** HTTP client — one you install and use in seconds, with no login, no clutter, no concepts to learn — has become surprisingly rare.

ApiScope is a deliberate step back to that. It does the one thing an IDE can *uniquely* do — **fill the request from your source code** — and refuses to grow into a platform. No account, no cloud, no mock, no orchestration; just a request you can send.

### What it is

ApiScope is **not** an API platform. It does exactly one thing that only an IDE plugin can do: **read your source and pre-fill the request**.

- Click the ➜ gutter icon next to a Controller method and the panel opens with the method, path, and the request DTO **recursively expanded into a full-field JSON body** — including superclass fields, generic substitution, `@JsonProperty` renames, and `@JsonIgnore` skips.
- `@RequestParam` types and the method's javadoc `@param` descriptions land in the Params table; optional params start unchecked.
- `@RequestHeader` / `@CookieValue` are added to the Headers / Cookie tabs (**added, never overwritten** — your hand-written auth headers survive).
- Un-annotated query objects (`list(OrderQueryDTO q)`) are flattened into query params.
- Unresolvable path placeholders like `{id}` are **kept and block sending**, instead of being silently replaced with an empty string that produces a "looks complete" but wrong URL.

### Why "lightweight"

- **One tool window is the whole thing.** No login, no account, no cloud sync, no Mock, no test orchestration.
- **The `core` layer has zero third-party dependencies.** Response prettifying is a hand-written single-pass scanner — no Jackson/Gson, so nothing clashes with the platform's bundled versions.
- **The request engine is the JDK's own `HttpClient`,** which is why it works on Community Edition.
- **No config files.** Install and use; changes on the panel take effect immediately. State lives in the project's `.idea/workspace.xml` (never committed).

### How it compares — and where it falls short

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

In short: ApiScope trades breadth for a single sharp edge — turning source you already have into a ready-to-send request in one click. Everything above is the price of staying that thin. See [Known limitations](#known-limitations-en) for the finer edges.

### Features

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

### Install

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
./gradlew :plugin:buildPlugin
# artifact: plugin/build/distributions/ApiScope-0.3.1.zip
```

IDEA → Settings → Plugins → ⚙ → **Install Plugin from Disk** → pick the zip → restart.
Or install from the [JetBrains Marketplace](https://plugins.jetbrains.com/) (search "ApiScope").

### Usage

1. Open any `@RestController` / `@Controller`; a ➜ icon appears in the gutter next to each method.
   (Keyboard: put the caret in a method and press `⌥⌘A` / `Ctrl+Alt+Shift+A`, or find *Open Endpoint in ApiScope* via `Cmd+Shift+A`.)
2. Click it → the `ApiScope` tool window opens on the right with method / path / full-field JSON body pre-filled.
3. Set the Base URL (e.g. `http://localhost:8080`; a candidate is offered from the module's `application.yml`), then **Send**.
4. Need auth? Add `Authorization: Bearer xxx` on the **Global Headers** tab — it's attached to every request afterwards.

Not starting from source? Just paste a full `http://...` URL into the path box (anything starting with `http://` is taken as-is).

<a name="known-limitations-en"></a>
### Known limitations

- Java source only (`language="JAVA"`); Kotlin Controllers aren't recognized yet.
- One request at a time (no tabs).
- One set of Global Headers; switch environments via a `token = ...` line on the Variables tab.
- Read timeout defaults to 300s (15s would cut the connection while you're stepping through a breakpoint); tune it in the title-bar gear menu. A 3s **connect** timeout catches typo'd addresses.
- The address bar and tables **preserve exactly what you typed**; percent-encoding is applied only at the last step before sending. Separators `&` / `=` are left alone, so encode them yourself if a value truly needs them.
- Cookies ignore expiry and `Secure`, and match by host suffix only.
- Responses over 512 KB are not prettified or highlighted.
- cURL import recognizes common flags only; the rest are reported, not guessed.

---

## 中文

IDEA 里的**简约 API 客户端** —— 一个长在 IDE 里的极简 Postman。

它不追求功能齐全,只做一件真正只有插件能做的事:**从 Controller 方法一键起手**,把 method、路径、
以及入参 DTO 递归展开成的**全字段 JSON body** 直接填好,剩下的地址你自己给。

Community 版可用(自带请求引擎,不依赖 Ultimate 的 HTTP Client)。

### 背景 —— 为什么再做一个

市面上的 API 工具几乎都长成了「**平台**」:Postman、Apifox、Insomnia 都堆上了账号、云同步、Mock、团队协作、脚本引擎…… 连 IDE 插件也走了同一条路,加环境集合、服务发现、请求树,一个版本比一个版本重。一个真正**轻量**的 HTTP 客户端 —— 装完即用、不用登录、界面不吵、没有新概念要学 —— 反而越来越少见了。

ApiScope 是有意的一次「往回退」:只做 IDE 才**独有**能做到的那件事 —— **从源码把请求填好** —— 并且拒绝长成平台。没有账号、没有云端、没有 Mock、没有编排,就是一条能发出去的请求。

### 对比主流 REST 客户端(含 ApiScope 的劣势)

ApiScope 是**刻意做窄**的:要完整 API 平台,请继续用 Postman / Apifox。就连其他*装在 IDE 里*的 REST 插件,也大多在往环境集合、服务发现、请求树上堆 —— ApiScope 全部有意不做(曾做过服务发现 + 网关前缀推断,实测「猜错地址」的排错成本高于「自己填一次」,整套移除)。下表如实列出 ApiScope **做不到**的事。

| 维度 | **ApiScope** | Postman / Apifox | IDEA 自带 HTTP Client(`.http`) | 其他 IDE REST 插件 |
|---|---|---|---|---|
| 运行形态 | IDE 内工具窗口 | 独立应用 / 网页 | IDE 内(**仅 Ultimate**) | IDE 内 |
| 从 Controller 读源码填 body | ✅ **存在的唯一理由** | ❌ 手填或从文档导入 | ❌ 手填 | ~ 部分有 |
| **Community 版**可用 | ✅(自带引擎) | 不适用 | ❌ | ~ 视情况 |
| 团队协作 / 分享 | ❌ 无 | ✅ 工作区、评论 | ~ 靠 git(文件) | ~ 视情况 |
| 云同步 / 账号 | ❌ 无,全本地 | ✅ | ❌ 本地文件 | ~ 视情况 |
| 环境 & 集合管理 | ❌ **无**(只有 `{{变量}}` + 历史) | ✅ 完善 | ✅ 环境文件、集合 | ✅ 多数有 |
| 多标签 / 请求树 | ❌ **一次一个请求** | ✅ | ✅(文件) | ✅ 多数有 |
| 协议 | **仅 HTTP/HTTPS** | HTTP、GraphQL、gRPC、WebSocket、SSE… | HTTP、部分 GraphQL/WebSocket | ~ 视情况 |
| 请求前/后脚本 | ❌ 无 | ✅ JS 沙箱 | ~ 有限 | ~ 视情况 |
| 自动化测试 / CI 运行 | ❌ 无 | ✅(Newman/CLI) | ~ 响应处理脚本 | ~ 视情况 |
| Mock 服务 | ❌ 无 | ✅ | ❌ | ~ 视情况 |
| 鉴权助手(OAuth2 流程、AWS 签名) | ❌ 手动粘 token | ✅ 内置流程 | ~ 有限 | ~ 视情况 |
| 扫描识别端点的语言 | **仅 Java** | 不适用 | 不适用 | ~ 视情况(部分支持 Kotlin) |

**ApiScope 明显更弱的地方(如实列出的劣势):**

1. **没有团队协作与云同步。** 它是单人、单机工具 —— 无法把请求集合分享给团队、评审、跨机器同步。这正是 Postman / Apifox 存在的意义。
2. **没有环境与集合管理。** 你只有 `{{变量}}` + 最近 20 条历史,仅此而已。要在多个服务里组织成百上千个端点,远弱于真正的 API 平台。
3. **一次只能开一个请求,无多标签。** 无法把两个接口并排对比,也留不住一整套常用请求。
4. **只扫 Java 源码。** Kotlin 写的 Controller 没有 gutter 图标、不会自动填充 —— 也就是说在 Kotlin 项目里,插件*全部*的优势都消失了。你仍可手敲 URL,但那时不如用独立工具。
5. **仅支持 HTTP/HTTPS。** 没有 GraphQL、gRPC、WebSocket、SSE、Socket.IO 的专门界面。非纯 HTTP 的接口请用 Postman / Insomnia。
6. **无脚本、无断言、无 CI。** 无法构建自动化回归套件,也无法用逻辑串联请求。Postman 脚本、IDEA `.http` 的响应处理器能做,ApiScope 不能。
7. **无 Mock 服务**、**无内置鉴权流程** —— bearer token 要你自己粘进 header 或变量。
8. **Cookie 处理刻意简化:** 只按 host 后缀匹配,且**忽略过期时间 / `Secure`**。长会话下可能带上过期 cookie(靠手动「清空 Cookie」兜底)。
9. **大响应不美化。** 超过 512KB 的响应体原样显示(不高亮、不折叠),以保证编辑器不卡。
10. **cURL 导入只认常见 flag** —— 其余报出来而不是猜。
11. **绑定 JetBrains IDE。** 离开 IntelliJ 就没法用,而且只有在你**打开了接口源码**时才发挥价值。要对一个你没有源码的第三方 API 做纯黑盒探索,独立客户端才是对的工具。

一句话:ApiScope 用「广度」换了「一把锋利的单刃」—— 把你手边已有的源码,一键变成一条可直接发送的请求。上面所有的缺失,都是保持这种「薄」所付的代价。更细的边界见[已知限制](#已知限制)。

### 功能

| 能力 | 说明 |
|---|---|
| Controller 方法旁 gutter 图标一键起手 | 填好 method / 路径 / 全字段 JSON body,直接发 |
| 入参 DTO 递归展开为**全字段** JSON | 含父类字段、泛型代入、枚举、日期、`@JsonProperty` 改名、`@JsonIgnore` 跳过 |
| Params 表的**类型 / 说明从源码读** | `@RequestParam` 的参数类型 + 方法 javadoc 的 `@param`;非必填参数默认不勾选 |
| `@RequestHeader` / `@CookieValue` 也读 | 分别补进 Headers / Cookie 页签(**只补不覆盖**,不会冲掉你手写的 Authorization) |
| 无注解参数也读 | `list(OrderQueryDTO q)` 摊开一层字段绑 query、`list(String kw)` 单行;`HttpServletRequest` 这类框架参数跳过 |
| Base URL 从 `application.yml` 提候选 | 读本 module 的 `server.port` / `context-path`,只当下拉里的一个候选项(地址仍由人给) |
| 键值页签都是表格 + 「批量编辑」 | Params / Headers / Cookie / 变量 / 表单字段;随时切回纯文本整段粘贴 |
| `@RequestParam` → `?a=&b=`,`{id}` 代样例值 | 生成的地址不会一发就 404 |
| Cookie 自动携带 | 响应的 `Set-Cookie` 按 host 存下,同 host 下次请求自动带上(可清空) |
| 复制为 cURL / 从剪贴板导入 cURL | 导出的是插值后的真实请求;导入不支持的参数会明确报出来,不静默丢 |
| Base URL 可编辑下拉 | 记住最近 8 个用过的地址,切环境就是换一项 |
| Body 六种格式 | none / JSON / XML / text / form-urlencoded / multipart(含文件上传) |
| `{{变量}}` 插值 | 地址、Headers、Body 里都能写 `{{baseUrl}}`、`{{token}}`,发送前统一替换 |
| 全局 Headers | 所有请求自动附加(鉴权 token 等);本次请求里写的同名 header 优先 |
| 请求历史 | 最近 20 条,一键回填 method / 地址 / headers / body |
| 响应体用平台 Editor 显示 | JSON 语法高亮、代码折叠、行号、`Ctrl+F` 搜索、软换行、非法 JSON 标红,全是平台白给的 |
| 响应区四个页签 | `Body` / `Headers n` / `Cookie n` / **实际请求**(真正发出去的那条 cURL) |
| 页签带数量 badge | 只给每次请求都会变的三个:`Params 3` / `Body n` / `Headers 2`。`Cookie / 全局 / 变量` 配好就不动,给它们加计数只会让标题栏跟着抖 |

### 安装

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
./gradlew :plugin:buildPlugin
# 产物:plugin/build/distributions/ApiScope-0.3.1.zip
```

IDEA → Settings → Plugins → ⚙ → **Install Plugin from Disk** → 选上面的 zip → 重启。
或在 [JetBrains Marketplace](https://plugins.jetbrains.com/) 搜索 "ApiScope" 安装。

调试运行(起一个带插件的沙箱 IDE,不影响主 IDE):

```bash
./gradlew :plugin:runIde
```

跑测试:

```bash
./gradlew :core:test    # 纯逻辑,秒级
./gradlew :plugin:test  # 真 PSI 环境,起轻量 IDE fixture
```

### 使用

1. 打开任意 `@RestController` / `@Controller`,方法左侧行号旁出现 ➜ 图标
   (不想用鼠标:光标停在方法里按 `⌥⌘A` / `Ctrl+Alt+Shift+A`,或 `Cmd+Shift+A` 搜 *Open Endpoint in ApiScope*)
2. 点图标 → 右侧 `ApiScope` 工具窗口打开,method / 路径 / 全字段 JSON body 已填好
3. 左上角填 Base URL(如 `http://localhost:8080`;能从本 module 的 `application.yml` 读出端口时会作为候选项给你),点「发送」
4. 需要鉴权就在「全局 Headers」页签写一行 `Authorization: Bearer xxx`,之后每次请求自动带上

不想从源码起手也行:Base URL 留着,把整条 `http://...` 地址直接粘进路径框即可(识别到 `http://` 开头就以它为准)。

#### Body 格式

Body 页签左上角切类型,**视图跟着换**:raw 三种给文本框,键值两种给表格。

| 类型 | 视图 | 发出去的 Content-Type |
|---|---|---|
| `none` | —— | 不带 body |
| `JSON` / `XML` / `text` | 平台 Editor(gutter 填的全字段 JSON 走这里):JSON 高亮 + 非法 JSON 直接标红,另可点「格式化」 | `application/json` / `application/xml` / `text/plain` |
| `form-urlencoded` | 键值表格 | `application/x-www-form-urlencoded` |
| `multipart/form-data` | 键值表格,选中一行点「选文件…」填路径 | `multipart/form-data; boundary=…`(自动生成) |

raw 和键值两种**各有独立缓冲**:在 multipart 里填的文件路径不会在切成 JSON 后被当成 JSON 文本发出去
(早先共用一个文本框时踩过这个坑,服务端报 400 但看不出原因)。文件不存在会直接报「上传文件不存在」,不会静默发个空段。

#### 键值表格与「批量编辑」

Params / Headers / Cookie / 全局 / 变量 / 表单字段,都是同一套表格:`☑ 启用 | 名 | 值 | 类型 | 说明`(「类型」只有 Params 有,只读、从源码读)。
末尾那行是**空占位行**,它的勾选框故意留空 —— 一敲名字勾就自动出现。选中一行按 delete/退格删掉整行。
右上角「批量编辑」切成纯文本,用来整段粘贴(从浏览器 devtools 拷一堆 header)、批量替换。

两个视图**无损往返**,因为**文本才是模型,表格只是视图**:勾选框没勾 ⇔ 文本里该行行首带 `#`。

```
表格:☐ keyword        # 可选筛选
文本:# keyword =      # 可选筛选
```

好处是落盘存的还是原来那段文本(见下文「没有配置文件」),老配置照样读。

#### 变量(`{{name}}`)

「变量」页签里每行一个 `name = value`(说明列写在同一行):

```
baseUrl = http://localhost:8080
token   = Bearer eyJhbGciOi...
orderId = 10086
```

之后 Base URL 写 `{{baseUrl}}`、路径写 `/order/{{orderId}}`、全局 Headers 写 `Authorization: {{token}}`,发送前统一插值。切环境只改 `baseUrl` 一行,别处全部跟着变。

**未定义的变量不会被悄悄当空串发出去** —— 发送会被拦下并红字列出缺哪个(地址少了一段却照发,比直接报错难查得多)。变量值里再写 `{{...}}` 不会二次展开(防自引用死循环)。

#### 面板布局

**上下分栏**:请求在上、响应在下,中间可拖(比例记住)。工具窗口常停在 IDE 右侧、只有 ~400px 宽,
左右分栏会把响应挤成一条缝,连状态码都看不全。

响应区头部一行给全 `HTTP 200 · 128ms · 899B`(Content-Type 在 tooltip 里 —— 窄窗口下它能占掉状态条一半宽度,而 Headers 页签本来就看得到)。
颜色只分两档:2xx/3xx 普通前景,4xx/5xx 与网络失败走主题的错误色(重定向不是错误,不值得单独占一个颜色);
面板底部左侧只放**发送前的输入问题**(未定义变量、地址不完整),右侧是这次请求来自哪个方法 —— 两类信息各有固定位置,不互相覆盖。

#### Cookie

三层,各自独立可用:

- **Cookie 页签**:手写 `name=value`,合成一个 `Cookie` 请求头(Headers 页签里手写的同名项优先)
- **响应 Cookies 页签**:解析响应的 `Set-Cookie`,名 / 值 / Domain / Path / Expires 都列出来
- **自动携带**:响应里的 `Set-Cookie` 按 host 存下,同 host 下次请求自动带上 ——
  「先调登录接口,再调业务接口」不用手工抄 session。工具窗口标题栏的齿轮菜单里可「清空自动收下的 Cookie」

刻意简化:只按 host 后缀匹配(`example.com` 的 cookie 会发给 `api.example.com`),**忽略过期时间**。
要正确处理 `Expires`/`Max-Age` 两种语义加时区,代码量超过整个 Cookie 功能;代价是长期用可能带上过期 cookie,靠「清空」兜底。

#### 导出 / 导入(工具窗口标题栏的齿轮菜单)

- **复制为 cURL** —— 导出的是**插值后、合并全局 Headers 和 Cookie 之后**的真实请求,贴到终端能直接跑
- **从剪贴板导入 cURL** —— 别人丢一条 cURL 过来,粘进来面板全填好。
  不认识的参数会明确报出来(`未支持的参数已忽略:--compressed`),不静默丢
- 复制响应体 / 保存响应到文件

**没有配置文件**。Base URL、全局 Headers、变量、Cookie、请求历史都存在 IDE 自己的项目配置里(`.idea/workspace.xml`),面板上改完即生效。

> 存 `workspace.xml` 而不是自己的 `.idea/apiscope.xml`:这里面装的是 token、session cookie 和含请求体的历史,
> 而 `.idea/` 下除 `workspace.xml` 之类以外的文件默认**跟着 git 走**。
> 从 0.2.0 升上来的话,旧的 `.idea/apiscope.xml` 会被自动读一次然后写到新位置;
> **如果它已经进过版本库,记得 `git rm --cached .idea/apiscope.xml` 并删掉本地文件**。

### 架构

```
core/     # 不依赖 IntelliJ API,也不依赖任何第三方库,可独立编译测试
  http/       JDK17 HttpClient 请求执行(RequestSender 接口便于测试替换)、
              body 编码(form-urlencoded、multipart boundary 拼装)、Cookie 解析 + cookie jar
  kv/         键值行 ⇄ 表格行(KvRow)互转,表格和「批量编辑」共用同一份文本模型
  json/       JsonSample(类型 → 树 → 文本,生成 body 用)、JsonPrinter(字符串 → 缩进,美化响应用)
  vars/       {{变量}} 插值
  endpoint/   端点描述 + query string 拼接与拆分 + 发送前的百分号编码
  export/     cURL 生成与解析(含分词器)
plugin/   # IntelliJ 层
  psi/        Controller 扫描、DTO → 全字段 JSON、映射注解读取、javadoc @param 读取、
              application.yml 里的 server.port 读取
  gutter/     行号旁发送图标
  ui/         请求面板(地址栏 / 六个页签 / 键值表格 / Body 编辑器 / 响应区 / 导出菜单各自成文件)
  actions/    「在 ApiScope 里打开当前接口」(编辑器右键 + Tools 菜单 + 可配快捷键)
  settings/   面板状态持久化(Base URL / 全局 Headers / 变量 / Cookie / 历史)
```

几处刻意的设计:

- **`core` 零第三方依赖**。插件里每多一个库,就多一次和 IntelliJ 平台自带版本冲突的机会 —— 所以响应美化是自己写的 60 行单遍扫描器,不引 Jackson/Gson
- **响应体用平台的 `EditorTextField` 而不是 `JBTextArea`**。高亮、折叠、行号、搜索、软换行、非法 JSON 标红全是白给的,自己写每样都要几十上百行。它的 editor 挂到界面时才创建、摘下时自动释放,所以**不能**手动 dispose
- **表格与「批量编辑」共用一份文本模型**,勾选框状态编码成行首 `#`。这样两个视图无损往返、落盘格式不变、老配置照样读 —— 换成结构化存储就要写迁移
- **cURL 解析用「带值 flag 白名单」而不是「无值 flag 黑名单」**。黑名单只保护已知 flag,遇到 `--retry 3` 会把 `3` 当 URL,之后全歪
- **响应美化只在字符串层面加缩进,不解析成对象**。第一个非空白字符不是 `{`/`[` 就原样返回:响应经常是 HTML 错误页或纯文本,把它们"格式化"坏了比不格式化更糟
- **JSON body 生成在 PSI 层递归展开**。泛型用 `PsiSubstitutor` 逐层代入(不是按名字猜)、父类字段沿继承链收集(分页参数常在基类)、自引用用路径集合 + 深度双重兜底
- **multipart 全程走字节流**(`ByteArrayOutputStream` 拼段 + `Files.readAllBytes`),绝不把文件内容当 String 拼 —— 二进制文件一过字符集就废了
- **插值发生在 UI 层,不在执行器里**。core 只管把给定的 body 编码发出去,不认识「变量」这回事;这样变量语义变化不会污染请求执行链
- **地址由人给,插件不猜**。曾经做过 Consul 服务发现 + 网关前缀推断 + 环境切换,实际用起来「猜错地址」的排错成本高于「自己填一次地址」,故整套移除
- **状态用 `PersistentStateComponent`**。要存的是列表 + 多行文本,`PropertiesComponent` 只适合单个标量
- **网络请求与 DTO 展开都不在 EDT 上**。前者放 pooled thread,后者放后台读线程(`ReadAction.nonBlocking`),否则 IDE 卡死

**为什么 core 不依赖 IntelliJ**:header 解析、JSON 格式化这类逻辑最容易出错也最需要测试,隔离出来就能用普通 JUnit 跑(`gradle :core:test`),不必起 IDE 沙箱。

### 已知限制

- 只扫 Java 源码(`language="JAVA"`),Kotlin 写的 Controller 暂不识别
- 一次只能开一个请求(没有多标签)
- 全局 Headers 只有一套,切环境时若鉴权不同需手改(改「变量」页签一行 `token = ...` 即可)
- 读超时默认 300 秒(断点调试时 15 秒会在你还在单步时就掐断连接),可在标题栏齿轮菜单「读超时…」改;「地址写错」由 3 秒的**连接**超时负责
- 表格与地址栏里**全程保留你敲的原文**(拼接与拆分必须互逆,否则回填时会悄悄改写你手改过的 URL),百分号编码只在**发送前最后一步**补上:值里的空格、中文都能直接敲,已经是 `%XX` 的不会被二次编码。分隔符 `&` / `=` 不动,所以值里真要用它们仍需自己 encode
- Cookie 忽略过期时间与 `Secure`,只按 host 后缀匹配
- 响应体超过 512KB 不美化也不高亮(卡的是 Editor 的高亮和折叠 pass,不是缩进本身)
- cURL 导入只认常见参数,其余报出来而不是猜

### 开发笔记

踩过的坑,重建时别再踩:

- 插件 `<description>` **不必全英文**,规则只是「开头得是拉丁字符 + 至少 40 字」——
  实测自 plugin-structure 的 `DescriptionNotStartingWithLatinCharacters`:
  *The plugin description must start with Latin characters and have at least 40 characters.*
  所以现在描述是「一段英文开头 + 中文正文」。另有 `HttpLinkInDescription` 规则,描述里别写 `http://` 链接
- 构建产物名来自 `intellijPlatform.projectName`,不是 Gradle 的 `base.archivesName`(设后者没效果)
- PSI 测试要两样东西:`testFramework(TestFrameworkType.Plugin.Java)`(`Platform` 里没有 `LightJavaCodeInsightFixtureTestCase`)+ 显式 `org.opentest4j:opentest4j`(平台测试框架不带这个传递依赖,缺了报 `NoClassDefFoundError`)
- PSI 测试用 `JAVA_LATEST_WITH_LATEST_JDK` 描述符,默认 mock JDK 里没有 `java.time` / `BigDecimal`,测不到标量分支
- `PersistentStateComponent` 的状态类必须是普通 class + `var` 字段 + 无参构造,**不能用 data class 的 `val`** —— XmlSerializer 靠反射读写可变属性,只读属性重启后会读成空
- wrapper 被插件生成时默认指向 Gradle 9.6,已手工钉回 **8.7**(本项目只在 8.7 上验证过)
- 增量构建产物会被污染,症状有两种:`jar` 报 `Entry xxx is a duplicate but no duplicate handling strategy has been set`,或 `compileTestKotlin` 报 `Module was compiled with an incompatible version of Kotlin`。**两者都是 `clean` 一次就好**,别去加 `duplicatesStrategy`(会连带影响 instrumented class 的取舍),也别去改 Kotlin 版本(配置里本来就只有一个 2.0.21)
- 必须 `JAVA_HOME` 指向 Oracle JDK 17,别用 JBR

## License

[MIT](LICENSE) © Red

图标(`plugin/src/main/resources/icons/`、`META-INF/pluginIcon.svg`)为原创几何牛头,随本仓库 MIT 授权分发。
