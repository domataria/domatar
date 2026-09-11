# Writing Domatar apps

This is the app-authoring specification. It describes the pattern every
Domatar application follows: manifest, hosts, container and leaf
classes, Navigator links, handlers, WUI servlets, install, and an
LLM-native facade.

Quippin is the worked *product* example ([Quippin](Quippin.md)). Read
that spec for quips, follows, and the directory — not for how to
structure an app. Bookstore, Money, Spreadsheet, and AI Agent use the
same pattern.

The present-tense statements describe the reference Java realisation
(Tomcat 10.1 / Jakarta EE 10 / Java 17, `domatar-core`). Domatar is a
protocol of objects, hosts, and messages; other realisations may exist
later. Direction: per-app processes ([Domatar](../Domatar.md) PART 16).

Identity (ownId, actId, usrId, binding) is
[Identifiers](../platform/Identifiers.md). How a message proves origin,
path, and TLS is [Security](../platform/Security.md).


## PART 1 — The pattern

Every app is a thin JAR under `apps/<appId>/`. It depends on
`domatar-core` (and the Servlet API for WUI classes). It never references
Tomcat, “the WAR I live in”, or a hardcoded context path.

Five structural rules (product-independent):

1. **Data is owned, not centralized.** User data lives on a sub-host.
   Shared directories, if any, live on the app's home host, owned by
   `<appId>@<appId>`.
2. **Containers and leaves.** The app node owns a few singleton container
   objects; each container holds many leaf objects via `lnk` rows.
3. **Everything is a Domatar object.** Addressable by `DomId`, browsable
   in Navigator, driven by the same handlers as the web UI and the LLM
   facade.
4. **Cross-provider is transparent.** `msgClient.send(dst, msg)` is the
   only send path; location is inside `HttpClient`.
5. **Installs are idempotent.** A second `installUser` is a no-op.

An app author writes:

| Piece | Typical package | Role |
| --- | --- | --- |
| `META-INF/domatar/app.manifest` | resources | AppId, install class, handlers, WUIs |
| `*Install` | `com.<app>.install` | `AppInstall` SPI |
| `*Impl` handlers | `com.<app>.objimpl` | `ObjImpl` / `DomatarInterface` |
| `*Wui` servlets | `com.<app>.webui` | `DomatarServlet` translators |
| HTML/JS/CSS/icons | `<appId>/assets/` | served by `AppAssetServlet` |
| App facade (optional) | same `objimpl` | coarse LLM-facing operations |


## PART 2 — app.manifest

`AppLoader` reads `META-INF/domatar/app.manifest` from each JAR in
`WEB-INF/apps/` ([Installation](../install/Installation.md)).

```
AppId:          quippin
Version:        1.0
InstallClass:   com.quippin.install.QuippinInstall
AssetDirectory: quippin/assets
ProviderHosts:  (optional, comma-separated; `{PrvId}` placeholder)

Handler: quippin, quip,  com.quippin.objimpl.QuipImpl
Wui:     QuipWui,        com.quippin.webui.QuipWui
```

Required: `AppId`, `InstallClass`, `AssetDirectory`.  
Optional: `Version` (default `1.0`), `ProviderHosts`.

- **Handler** lines are `(clsAppId, clsId, className)` registered in
  `ImplMap`.
- **Wui** lines are `(WuiName, className)` mapped at
  `/domatar/<appId>/Wui/<WuiName>`.
- Assets are served from `AssetDirectory` inside the JAR.


## PART 3 — Hosts

`DomId.HOST_SEP` is `-` (do not use `~`; that character is in the
fingerprint alphabet).

**Home host** (every app): `hstId = appId`, with home user
`<appId>@<appId>` — the owner of the app
([Domatar](../Domatar.md) PART 4.1.1). `/Setup` on the offering provider
creates both. Shared objects (directory, registry, catalog) live here
if the app has any; they are owned by that account.

**Sub-host** (`appId` + `-` + suffix): the suffix is the app's choice
(printable ASCII except `.`). Common patterns:

```
hstId = DomId.subHstId(appId, actId)           // <appId>-<actId>
        or DomId.subHstId(appId, actId, prvId) // replica on that provider
```

Put the user's containers and leaves on a sub-host if that is how the
app is structured. Shell UIs (login, desktop, navigator) use the
provider-qualified form on each login home. An app may instead create
hosts per directory, per function, or any other partition.

The platform app `domatar` currently has no global home host; each
provider uses local sub-hosts `domatar-<prvActId>`. That is open, not
a prohibition.

**Provider hosts** (optional): `ProviderHosts` in the manifest, e.g.
aggregation hosts. Empty is fine.

Do not invent a second addressing scheme. If you need a shared object,
put it on a named host and send messages to its `DomId`.


## PART 4 — Classes, containers, links

Classes are the pair `(clsAppId, clsId)`. `clsAppId` is usually the
appId. Container classes (one object per user) and leaf classes (many
objects) are both ordinary classes.

Navigator parent→child edges are `lnk` rows. Typical install skeleton:

1. Create container objs with `ObjDb.addObjIfMissing`.
2. Create the `app-<appId>` node (`clsId` = `app`).
3. Link containers under the app node (`ClsInstall` / `LnkDb`).
4. Link the app node from the user's Navigator root (or rely on
   `NavRootReconcile` / `GetUserApps` for launcher inventory — the
   platform substrate owns the installed-app list).

`ClsInstall` / `SrvInstall` write class and service descriptors on the
host so Navigator and the LLM primer can name the operations.

Object identifiers: `IdGen.getId("quip")` (time-ordered) or
`IdGen.createId("follow", key)` (stable from a natural key).


## PART 5 — Install SPI

Implement `com.domatar.install.AppInstall`:

```java
void installProvider(String prvId, String domain);

void installUser(String actId, String usrId, String usrName,
                 String prvId, String domain,
                 DomatarMsgClient msgClient);
```

- `installProvider` — catalog row, any provider-scoped hosts. Called
  from `/Setup` / marketplace when the JAR is on the provider.
- `installUser` — per-account objs and links. Called at sign-up for
  default apps and from App Store `InstallApp`.

Always go through `ObjDb.addObjIfMissing` / link-if-missing. Register
the app in the provider catalog with `CatalogInstall.registerInCatalog`.

Sign-up also creates the **platform user substrate**
`domatar-<actId>-<prvId>` (membership, binding, userApps, shells) before
shell apps install. App authors do not recreate that graph. See
[Platform App](../platform/Platform-App.md).


## PART 6 — Handlers (`ObjImpl`, `ImplMap`)

Behaviour is a class implementing `DomatarInterface`, normally by
extending `ObjImpl`. `ImplMap` looks up `(clsAppId, clsId)`:

```java
String handleMsg(String msg, Obj obj, String contextPath,
                 String contextRealPath, DomatarMsgClient msgClient)
```

Idiomatic body:

```java
JsonMsg inMsg  = new JsonMsg(msg);
String  opr    = inMsg.getOperation();
JsonMsg outMsg = new JsonMsg();

if (!hasRights(inMsg, obj, msgClient))
  return outMsg.addError(opr, "Unauthorized").toString();

switch (opr)
{
  case "PostQuip":
    // ...
    outMsg.addResponseBody(opr, attrs);
    break;
  default:
    outMsg.addError(opr, "Unknown operation");
}
return outMsg.toString();
```

Types the author uses (`com.domatar.*`):

- `util.JsonMsg`, `util.ObjAttrs` — the message
- `util.DomatarMsgClient` — `send(DomId, JsonMsg)`, `withToken`, `domIdPath`,
  `alreadyEntered`, `priorVisitCount`, `priorVisitCountAny`, `outMsgs`,
  `attach`, `attachment`
- `util.ObjImpl`, `util.DomatarInterface` — handler base
- `util.DomId`, `util.Obj`, `util.Lnk` — value types
- `db.ObjDb`, `db.LnkDb`, `db.ActDb`, `db.HstDb` — persistence
- `core.Auth` — `isVerified(inMsg)` (ACCOUNT shortcut; not a DB hit)
- `core.Context` — `trust`, `actId`, `contextId`; `isVerified()` is the
  ACCOUNT shortcut of the three-valued verdict
- `servlet.DomatarServlet` — WUI base

Verification is unconditional at every HTTP boundary
([Security](../platform/Security.md) PART 8.9, 11.3). Override
`ObjImpl.requiresPath()` only to declare that a Trust.NONE / Trust.PATH
verdict is FATAL for this class. Apps never set the envelope, never
construct a Context, and never import `com.domatar.crypto`. A handler
receives a `DomatarMsgClient` that can `send` (and `withToken` /
`domIdPath`) but cannot `root`, plus a `Context` carrying trust, actId
and contextId. The same client answers `alreadyEntered` /
`priorVisitCount` / `outMsgs` and `attach` / `attachment` for this
object ([Domatar](../Domatar.md) PART 6.3, PART 7). Handlers query;
they do not INSERT. `Context.getContextId()` is the current lineage;
`attach(contextId, msgName, slot, …)` names a past visit. A ContextId
is not authorization.


## PART 7 — WUI servlets

A WUI servlet is a translator, not business logic. Subclass
`DomatarServlet` and implement `getMsg(...)`: read HTTP parameters,
build one `JsonMsg` to one object (set the class envelope when talking
to a container), return it. The base class verifies the session,
sends the message, and writes JSON to the browser.

Frontend assets never speak SQL or Java — only this round-trip.
`AppLoader` registers each manifest `Wui` at
`/domatar/<appId>/Wui/<WuiName>`.

The platform `Msg` servlet (`/domatar/Msg`) is the cross-provider door.
App authors do not write one.


## PART 8 — Messages

A message is `JsonMsg`: Head (Method, SrcId, DstId, TimeStamp, optional
class/service envelope, Context) and Body (Operation, Attrs, Error).
The same shape is a browser click, an in-JVM call, and a cross-provider
hop.

```java
JsonMsg out = new JsonMsg();
ObjAttrs a  = new ObjAttrs();
a.addAttr("QuipId", quipId);
out.addResponseBody("PostQuip", a);
```

`msgClient.send(dst, msg)` stamps Head and dispatches: same `hstId` as
`DOMATAR_HSTID` → in-process; else HTTP POST to
`http://<domain>/domatar/Msg`. Empty `dst.hstId` means “on my host”.

LLM-oriented field names and side-effect tags: [LLM Messages](../platform/LLM-Messages.md)
and [Service](../platform/Service.md).


## PART 9 — LLM-native facade

Expose a small set of coarse operations on the app node (or a dedicated
facade object) so an agent can `useApp(appId)` and receive typed tools
from the class descriptor. Do not require the LLM to invent `clsId`s or
raw `SendMsg` strings.

Quippin's `QuippinAppImpl` is the product example of that facade;
the mechanism is platform-wide ([AI Agent](AIAgent.md) PART 16).


## PART 10 — Packaging and layout

Add a Maven module under `apps/<appId>/` with parent `com.domatar:domatar`.
Depend on `domatar-core`. `finalName` should match `appId` so
`AppLoader` sees `<appId>.jar`. The platform WAR copies app JARs into
`WEB-INF/apps/` at package time.

```
apps/<appId>/
  pom.xml
  src/main/java/com/<appId>/install/
  src/main/java/com/<appId>/objimpl/
  src/main/java/com/<appId>/webui/
  src/main/resources/META-INF/domatar/app.manifest
  src/main/resources/<appId>/assets/
```

Build with `mvn -q -pl platform/war clean package -am -DskipTests`.
Always `clean package` (never bare `package`) so app JARs are not ECJ
stubs.

Icons: [Icons](../platform/Icons.md). Code style:
[Code Style](../platform/CodeStyle.md).
