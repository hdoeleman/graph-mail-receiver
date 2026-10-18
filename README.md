# graph-mail-receiver

Java 25 / Spring Boot 4 application that pulls email from a mailbox through the Microsoft Graph API and shows sender, date, subject and body in a web page. It can fetch mail three ways, chosen per pull in the page:

| Mode | How it gets mail |
|---|---|
| **demo** (default) | Randomly generated emails. Graph is not called, no credentials needed. |
| **rest** | The Graph REST API through a Spring `@HttpExchange` interface (`GraphMailApi`), with app-only tokens from Spring Security's OAuth2 client. |
| **sdk** | The Microsoft Graph Java SDK (`GraphServiceClient`), with app-only tokens from azure-identity. |

The web page and its `/api` endpoints are **temporary**: they exist only to try the Graph integration by hand and will be removed. They have no authentication and listen on all network interfaces, so don't run them with real credentials on a shared network.

Design decisions: [docs/adr/0001-graph-mail-retrieval-design.md](docs/adr/0001-graph-mail-retrieval-design.md).

## How it works

The app **pulls** mail from Graph on demand. Graph can also push: change notifications by webhook, or via Azure Event Hubs or Event Grid. This app doesn't use them, because a webhook needs a publicly reachable HTTPS endpoint and subscriptions that are renewed every few days (see the ADR). The page's mode switch (Demo / REST / SDK) picks the mode, and **Pull emails** calls `GET /api/emails?mode=<mode>&limit=N`. `GET /api/modes` tells the page which modes can be used and which one to preselect. The page remembers your last choice in the browser.

In the rest and sdk modes, the backend:

1. gets an app-only token (OAuth2 client credentials) from `login.microsoftonline.com`, and caches it until shortly before it expires;
2. calls `GET /users/{mailbox}/mailFolders/inbox/messages?$top=N&$orderby=receivedDateTime desc&$select=id,subject,from,receivedDateTime,body` with `Prefer: outlook.body-content-type="text"`, so bodies come back as plain text;
3. returns `{id, senderName, senderAddress, subject, receivedAt, body}` per email.

If Graph rejects the token with a 401, the token is dropped and the request is retried once with a new one. After a 403 the pull fails, but the next pull uses a new token, so a permission you just granted takes effect right away.

Every call to Entra ID and Graph, in both modes, times out after 5 s connecting or 30 s waiting for a response (`spring.http.clients.*` in `application.yml`), so a stalled connection fails the pull instead of hanging it. In sdk mode, the SDK also retries throttled requests (429/503/504) by itself, and azure-identity retries failed token requests; rest mode does neither.

The page renders email content with `textContent` only, never as HTML.

### New mails only

Tick **New mails only** and **Pull emails** fetches only the mails no earlier new-mail pull stored (`POST /api/emails/new?mode=<mode>`), and stores them in an H2 file database (`./data/graph-mail.mv.db` by default). **Show stored** lists them (`GET /api/emails/stored`). Mail seen only by regular pulls isn't stored, so it still counts as new.

It uses a Graph delta query: `GET /users/{mailbox}/mailFolders/inbox/messages/delta?changeType=created&$select=...&$filter=receivedDateTime ge <since>`, following `@odata.nextLink` pages until an `@odata.deltaLink`. That deltaLink is saved, and the next pull follows it to get only mail created since. Graph tracks the sync position; the app stores the mails and skips ids it already has.

- A mail counts as new when its id isn't stored yet, whichever mode fetched it. Delta requests ask for immutable ids (`Prefer: IdType="ImmutableId"`), which stay the same when a mail moves between folders, so a mail that arrives twice is stored once.
- Each page's new mails are saved in one transaction together with the sync's progress: the next page's link while the sync is unfinished, the deltaLink once it completes. A pull that stops part-way, on a failure or after 200 pages (about 10,000 mails), continues on the next pull from the page it reached; the response then says `"complete": false`.
- Each sync remembers where it started: the newest stored mail, or `graph.initial-sync-window` ago (default 30 days) when nothing is stored. Outlook keeps delta tokens in a limited cache, so a link can expire at any time; Graph then answers `410 Gone`, or a 4xx with an error code such as `syncStateNotFound`. The app starts the sync again from where it started (`receivedDateTime ge <start>`). Mail the interrupted sync hadn't reached is not skipped, and older mail is not fetched again. A restart link Graph may send in `Location` isn't used: it repeats the very first, unbounded query.
- With a `receivedDateTime` filter, Graph returns at most 5,000 messages per sync. The query asks for them newest first (`$orderby=receivedDateTime desc`), and a sync that reaches 5,000 logs a warning, since older mail in its range may then be missing. A first sync over a busy mailbox may need a shorter `GRAPH_INITIAL_SYNC_WINDOW`.
- **Reset sync** (`POST /api/sync/reset`) forgets the links; the next pull starts a new sync the same way. Nothing is stored twice.
- rest and sdk mode share one sync state per mailbox: Graph's links are plain URLs either can follow.
- In demo mode, **New mails only** makes up 0 to 3 new mails per pull and stores nothing.

The database file holds real mailbox content, unencrypted; `data/` is git-ignored. Flyway creates the schema (`src/main/resources/db/migration`). Delete `data/` to start over. At startup Flyway 12.4 warns that H2 2.4 is newer than the H2 version it has been verified with (2.3); that's expected with the versions Spring Boot 4.1.1 manages.

## Run

Demo mode is the default. It serves randomly generated emails and needs no credentials:

```bash
JAVA_HOME="C:/Program Files/Java/jdk-25.0.4" mvn spring-boot:run
```

Then open http://localhost:8080. Without Graph settings, REST and SDK are greyed out; hover over them to see which settings are missing.

### Real Graph (rest and sdk modes)

To read a real mailbox you need a Microsoft 365 / Exchange Online mailbox, and an Entra ID admin who can grant consent. The app signs in as itself (no user password), so you create an app registration once. Both Graph modes use the same registration and settings.

#### 1. Register the app (Entra admin center, https://entra.microsoft.com)

1. **Identity > Applications > App registrations > New registration.** Name it (e.g. `graph-mail-receiver`), keep "Single tenant", leave the redirect URI empty, and click Register.
2. On the app's **Overview** page, copy:
   - **Directory (tenant) ID** → `GRAPH_TENANT_ID`
   - **Application (client) ID** → `GRAPH_CLIENT_ID`
3. **Certificates & secrets > New client secret.** Copy the secret **Value** (not the Secret ID) right away, because it is shown only once → `GRAPH_CLIENT_SECRET`.
4. **API permissions > Add a permission > Microsoft Graph > Application permissions > `Mail.Read`**, then click **Grant admin consent**. It must be an *Application* permission, not Delegated, or the call fails.

#### 2. Choose the mailbox

`GRAPH_MAILBOX` is the mailbox's email address / user principal name (e.g. `inbox@yourdomain.com`) or its user object id. The app reads that mailbox's **Inbox**.

`Mail.Read` as an application permission lets the app read **every** mailbox in the tenant. Restrict it to just this mailbox with an Application Access Policy (Exchange Online PowerShell):

```powershell
New-ApplicationAccessPolicy -AppId <client-id> -PolicyScopeGroupId <mail-enabled-security-group> `
    -AccessRight RestrictAccess -Description "graph-mail-receiver"
```

The group contains only the allowed mailbox. Policies can take up to an hour to apply. RBAC for Applications is Microsoft's newer alternative.

#### 3. Give the app the values

Set these settings. Never commit the secret.

| Setting | Meaning |
|---|---|
| `GRAPH_MODE` | Optional: the mode the page preselects, `demo` (default), `rest` or `sdk`. An empty value counts as unset |
| `GRAPH_TENANT_ID` | Directory (tenant) ID |
| `GRAPH_CLIENT_ID` | Application (client) ID |
| `GRAPH_CLIENT_SECRET` | Client secret value |
| `GRAPH_MAILBOX` | Mailbox address or object id |
| `GRAPH_INITIAL_SYNC_WINDOW` | Optional: how far back the first new-mail pull looks when nothing is stored, e.g. `7d`. Default `30d` |
| `GRAPH_DB_URL` | Optional: JDBC URL of the store. Default `jdbc:h2:file:./data/graph-mail`, relative to the folder the app runs in |

When all four Graph settings are set, the REST and SDK modes become available in the page. If `GRAPH_MODE` is `rest` or `sdk` and any of them is empty, the app refuses to start and names the missing ones. Surrounding whitespace in the ids and the mailbox is ignored.

**Option A: environment variables** (set them in the same terminal that starts the app).

PowerShell:

```powershell
$env:GRAPH_MODE = "rest"
$env:GRAPH_TENANT_ID = "<tenant-id>"
$env:GRAPH_CLIENT_ID = "<client-id>"
$env:GRAPH_CLIENT_SECRET = "<secret-value>"
$env:GRAPH_MAILBOX = "inbox@yourdomain.com"
mvn spring-boot:run
```

Git Bash: use `export GRAPH_TENANT_ID=...` and so on.

**Option B: a local config file** `config/application.yml` in the project folder. Spring Boot loads it automatically and it overrides the defaults. The `config/` folder is git-ignored.

```yaml
graph:
  mode: rest
  tenant-id: <tenant-id>
  client-id: <client-id>
  client-secret: <secret-value>
  mailbox: inbox@yourdomain.com
```

#### 4. Verify

Start the app. The log should show `Mail modes available: [DEMO, REST, SDK]`. On http://localhost:8080, REST and SDK are now clickable. Choose **REST** and click **Pull emails**: the log shows the token request (`--> POST https://login.microsoftonline.com/...`), then `--> GET https://graph.microsoft.com/...` and `<-- 200 OK`, and `REST: Graph returned N emails`. Choose **SDK** and pull again: the log shows `SDK: Graph returned N emails` (the SDK's own requests are not logged line by line).

#### Troubleshooting

The page and the log both show the upstream error; the log also has the stack trace.

| Symptom | Likely cause |
|---|---|
| REST and SDK are greyed out | one of the four Graph settings is empty where the app was started; hover over the option to see which |
| App doesn't start: `graph.mode is rest, but these settings are empty: ...` | the named settings aren't set in the terminal (or config file) that started the app |
| App doesn't start: `Failed to bind properties under 'graph.mode'` | `GRAPH_MODE` isn't `demo`, `rest` or `sdk`, e.g. a typo |
| `invalid_client` on the token request | wrong client id or secret, you copied the Secret ID instead of the Value, or the secret expired |
| `403` on messages | `Mail.Read` missing, not an *Application* permission, admin consent not granted, or the Application Access Policy excludes this mailbox (allow up to an hour). After fixing it, just pull again: a 403 makes the app use a new token |
| `404` on messages | `GRAPH_MAILBOX` is wrong, or the mailbox has no Exchange Online license |
| A timeout exception in the log: `HttpTimeoutException` (rest), `SocketTimeoutException` (sdk, Graph request) or a timeout from azure-identity (sdk, token request) | Entra ID or Graph accepted the connection but didn't answer within 30 s; check the network or proxy |
| `HttpConnectTimeoutException` (rest) or `SocketTimeoutException: Connect timed out` (sdk) in the log | Entra ID or Graph couldn't be reached within 5 s; check the network or proxy |

## Dependency versions

Spring Boot 4.1.1 manages almost all versions. Three are overridden in `pom.xml` with newer patch releases, because the versions Boot manages have published security advisories: Jackson 2 (`jackson-2-bom.version`), Jackson 3 (`jackson-bom.version`) and Tomcat (`tomcat.version`). Remove each override once a Spring Boot release manages that version or a newer one.

To check the resolved dependencies for known vulnerabilities, run `mvn dependency:list -DincludeScope=test` and look the versions up in [OSV.dev](https://osv.dev) (the data behind GitHub's advisories), or watch the repository's Dependabot alerts.

## Tests

```bash
JAVA_HOME="C:/Program Files/Java/jdk-25.0.4" mvn test
```

- `DemoMailSourceTest`: generated data (count, order, completeness, determinism).
- `GraphPropertiesTest`: whitespace stripping, each missing Graph setting, the token endpoint, and the shipped defaults: demo mode and a 30-day `graph.initial-sync-window`.
- `MailSourceConfigTest`: which modes are available with and without Graph settings, how `graph.mode` picks the default, and the startup failure when a Graph mode is chosen without settings. It runs without environment variables, so your exported `GRAPH_*` values can't affect it.
- `MailControllerTest`: JSON output, mode per pull, unknown or unavailable modes, limit clamping, 502 on upstream failure, `/api/modes`, and the new-mail endpoints: `POST /api/emails/new` (chosen or default mode, 502 on failure), the stored-mail limit clamp (both bounds), and the 204 from `POST /api/sync/reset`.
- `GraphMailReceiverApplicationTest`: full context pinned to demo mode, the shipped HTTP timeouts, UI served with the mode switch.
- `RestModeIntegrationTest`: full context in rest mode against `FakeGraphServer`, a local stand-in for Entra ID and Graph: token request, Graph request shape and mapping, token caching, retry after 401, new token after 403, token errors, and timeouts of both the token and the Graph request.
- `SdkModeIntegrationTest`: full context in sdk mode against `FakeGraphServer`, with a stub credential instead of the token endpoint (azure-identity only talks to https authorities): Graph request shape, token scope and mapping through the SDK, client and credential reuse, a new credential after a 401 (retried once) and after a 403, the SDK's retry of a throttled request, a failed token request, and the Graph request timeout.
- `SdkMailSourceTest`: the SDK's HTTP client gets the configured timeouts and keeps Graph's retry middleware.
- `NewMailServiceTest`: the new-mail sync against the real Flyway schema, with a scripted delta source: the first sync's look-back window, paging, storing only unseen mail with its full content, an expired link with and without a restart link, a first or incremental sync interrupted and resumed or restarted from where it started, the page cap, a page that fails to store being rolled back with its progress, reset, per-mailbox scoping, demo mode, and pulls being serialized.
- `RestModeIntegrationTest` and `SdkModeIntegrationTest` also cover each mode's delta requests against `FakeGraphServer` (the query, following links, skipping removed entries, `410 Gone` with and without `Location`) and a full new-mail pull into the database.
- Tests that use the database run on their own embedded database (`@AutoConfigureTestDatabase`), so a local `config/application.yml` or `SPRING_DATASOURCE_URL` can't point them at your real one.

## Logging

`org.example.graphmail` logs at DEBUG by default: each pull and its mode, every outgoing request in rest mode (the token request and the Graph request) with its status and latency, token drops and retries in both Graph modes, and generated demo data (TRACE adds per-email lines). The client secret and access tokens are never logged. Change levels in `application.yml` or with `LOGGING_LEVEL_ORG_EXAMPLE_GRAPHMAIL=TRACE`.
