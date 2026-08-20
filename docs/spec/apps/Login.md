# SPEC: LOGIN APP - identity-management UI for Domatar

This document specifies the Login app, the user-facing app that
exposes the federated-identity protocol described in [Login protocol](Login-Protocol.md).

  - [Login protocol](Login-Protocol.md)    : the protocol (actId vs usrId, central hosts,
                        verify-at-every-seam, instant logout, etc.).
  - [Login](Login.md) : THIS file. The Domatar app that gives users
                        a UI for sign-up, sign-in, multi-app linking,
                        and profile edits. Membership lives on the
                        per-provider replica login-<actId>-<prvId>
                        ([Login protocol](Login-Protocol.md)). Account UI
                        reads membership and shells via UserSubstrateWui
                        on domatar-<actId>-<prvId>
                        ([Platform App](../platform/Platform-App.md)).

## PART 1 — PURPOSE

The Login app is a Domatar app whose only job is identity management.
Every protocol primitive in [Login protocol](Login-Protocol.md) has a UI here:

  - First sign-up                    [Login protocol](Login-Protocol.md) PART 4
  - Sign-in                          [Login protocol](Login-Protocol.md) PART 6
  - Add another app login            [Login protocol](Login-Protocol.md) PART 5
  - Rename usrId (<localname> only)  [Login protocol](Login-Protocol.md) PART 3.2
  - Change usrName                   [Login protocol](Login-Protocol.md) PART 3.3
  - Change password                  [Login protocol](Login-Protocol.md) PART 11.1
  - Remove a linked login            [Login](Login.md) PART 8.6
  - Sign out                         [Login protocol](Login-Protocol.md) PART 8

Two design constraints are worth stating up front:

  C1. The Login app does NOT replace any per-app central host's
      ActManager. Each app's ActManager remains the authority for
      its own act rows and passwords. The Login app is a CLIENT of
      every app's ActManager - it just gives the user a unified
      place to issue those ops. Adding a Spreadsheet login from the
      Login app sends AddAct to spreadsheet.domatar.com; renaming a
      Quippin usrId sends UpdateAct to quippin.domatar.com.

  C2. The Login app has its own central host ("login") and its own
      act table on that host, just like every other app. A user who
      "just wants a Domatar identity, no specific app in mind" signs
      up via the Login app and gets a key-fingerprint actId minted
      server-side. From that root, they can later link Quippin /
      Spreadsheet / etc. logins.

## PART 2 — APP IDENTITY

  appId          : "login"
  central host   : login                  (resolved via the directory)
  per-user host  : login-<actId>-<prvId>
  hosting prv    : each login-home provider (same replica convention as
                   desktop / navigator; see [Login protocol](Login-Protocol.md)).

  Host ids use `-` as separator (`DomId.HOST_SEP`). actId is an opaque
  32-char fingerprint; it is never user-visible or user-chosen.

The central host serves three roles:

  1. The verifier for any usrId of shape "<localname>@login" - same
     contract as quippin's central host serves for "@quippin"
     usrIds ([Login protocol](Login-Protocol.md) PART 6).
  2. The host of the public sign-up / sign-in pages. login.domatar.com
     in production; tomcat1 with a "login" alias in the local sim
     (PART 12).
  3. The host of the SSO iframe described in [Login protocol](Login-Protocol.md) PART 7.2
     for users rooted at @login. This is just the per-app SSO
     iframe, not a special "Login app" thing.

The per-user sub-host login-<actId>-<prvId> is each user's private
container for "things the Login app remembers about me" — chiefly the
membership directory (PART 3.2). Same replica shape as
desktop-<actId>-<prvId>.

## PART 3 — DATA MODEL

3.1 Act rows on the Login app's central host

Identical schema to any other app's act table ([Login protocol](Login-Protocol.md)
PART 3.4):

  HstId    = login
  AppId    = act
  ActId    = act@act
  ObjId    = <usrId>
  ClsAppId = act
  ClsId    = act
  Attrs    = {
    "ActId":     "<fingerprint>",    (32-char key fingerprint, minted server-side)
    "UsrId":     "<localname>@login", (login label; human-chosen; may change)
    "UsrName":   "<display name>",
    "HashedPwd": "<argon2/scrypt>",
    "Salt":      "<per-row salt>",
    ...
  }

The usrId "@login" suffix tells the rest of the system which
central host routes for this user. The actId is an opaque
fingerprint (not a usrId).

3.2 Linked-logins directory on login-<actId>-<prvId>

For each user, a per-user replica login-<actId>-<prvId> holds a
directory of EVERY usrId currently linked to that actId, across all apps.
This is what powers the "manage my logins" UI without having to
poll every app's central host.

  Container obj
  -------------
    HstId    = login-<actId>
    AppId    = login
    ActId    = <actId>
    ObjId    = logins                  (singleton; like Quippin's
                                        "follows" container)
    ClsAppId = login
    ClsId    = logins
    ObjName  = "Logins"
    ObjDesc  = "Logins linked to this account"

  Per-link rows
  -------------
    HstId    = login-<actId>
    AppId    = login
    ActId    = <actId>
    ObjId    = login-<usrId>           (e.g. login-dave@quippin)
    ClsAppId = login
    ClsId    = login
    ObjName  = "<UsrName>"
    ObjDesc  = "Linked login at <appId>"
    Attrs    = {
      "UsrId":     "<localname>@<appId>",
      "UsrName":   "<display name>",
      "AppId":     "<appId>",
      "AddedAt":   "<base64 time>",
      "IsRoot":    "True"|"False"      (the act row whose @<appId>
                                        suffix matches the actId's
                                        @<appId> suffix is the
                                        ROOT - the original sign-up)
    }

When the user signs up for the first time (via any app, not just
Login), the originating app SHOULD create a row in login-<actId>
recording their root login. When the Login app's UI is the agent
adding/removing/renaming a login, it updates this directory inline.

For act rows that exist on per-app central hosts but are NOT
recorded in login-<actId> (e.g. created directly via that app's
own AddAct, never going through the Login app), the user can
"import" them by entering the usrId+password in the Login app's
"Import existing login" form (PART 8.7). The Login app verifies
the credentials, confirms the act row's actId matches the user's
current actId, and creates the directory row.

3.3 ObjId family

  "login-..." rows live entirely under HstId=login-<actId> and
  ActId=<actId>, so they cannot collide with any other app's
  rows. Lexicographically "login-" sorts at 'l', between "like-"
  and "log-"; the prefix-scoped purge fix in ObjDb.deleteOldObjs
  ([Domatar](../Domatar.md)) means only an explicit "login"-prefix purge would
  ever touch these rows.

## PART 4 — WEB UI

  /<context>/login
      Public sign-in page. Loads the SSO iframe ([Login protocol](Login-Protocol.md)
      PART 7.2) pointing at the central host of whatever app the
      user wants to verify against. Existing login.html is this
      page; the Login app inherits ownership.

  /<context>/signup
      Public first-time sign-up page. Form fields: localname,
      usrName, password. POSTs to AccountsWui with Action=SignUp,
      which dispatches AddAct to the Login app's central host
      (or, if the user picks a different app from a dropdown, that
      app's central host).

  /<context>/account
      Logged-in management page. Shows:
        - Current usrId, usrName, root login flag.
        - List of all linked logins (read from login-<actId>).
        - Buttons: "Add login", "Rename usrId", "Change usrName",
          "Change password", "Remove this login", "Sign out".
        - Provider-only "Site customization" card (default apps for
          new accounts). Rendered only when GetDefaultAppsConfig
          returns IsProvider=True; dispatches to the domatar-app
          SiteConfigWui at /domatar/domatar/Wui/SiteConfigWui — NOT
          through AccountsWui ([Provider Customization](../install/Provider-Customization.md)).
      Each identity button opens a small form that posts to AccountsWui.

  /<context>/icons/login.svg
      Static icon asset, contributed to Desktop's catalog
      ([Desktop](Desktop.md) PART 8).

The pages are intentionally thin. All real work happens in
AccountsWui (PART 5) and on the central hosts of whatever app
the operation targets.

## PART 5 — WUI SERVLET (AccountsWui)

  com.login.webui.AccountsWui  @WebServlet("/AccountsWui/*")
  extends com.domatar.servlet.DomatarServlet

Single entry point for every UI action. Each Action dispatches to
the appropriate Domatar primitive on the appropriate destination,
exactly as specified per-operation in PART 8.

  Action=SignIn         (public; not actually a Domatar message,
                         this is the existing login.html / iframe
                         flow)
  Action=SignUp         dispatches AddAct to <appId>'s central host
  Action=AddLogin       dispatches AddAct to <appId>'s central host
  Action=RenameUsrId    dispatches UpdateAct to <appId>'s central host
  Action=ChangeUsrName  dispatches UpdateAct to <appId>'s central host
  Action=ChangePwd      dispatches ChangePwd to <appId>'s central host
  Action=RemoveLogin    dispatches DeleteAct to <appId>'s central host
  Action=ImportLogin    dispatches VerifyLogin then writes login-<actId>
  Action=ListLogins     reads login-<actId>'s logins container
  Action=SignOut        dispatches Logout to <appId>'s central host
                        (the verifier of the current session)

The destination's <appId> is parsed from the relevant usrId in the
form body. For ListLogins it is implicit (the local user's actId).

AccountsWui DOES NOT verify credentials itself - DomatarServlet's
outer trust boundary already does that ([Login protocol](Login-Protocol.md) PART 9).
For public actions (SignIn, SignUp, ImportLogin), the inMsg may
have an unverified Context; the central host's hasRights for those
ops is "public" ([Domatar](../Domatar.md) PART 9).

## PART 6 — OBJ HANDLER (LoginsImpl)

  com.login.objimpl.LoginsImpl  extends com.domatar.util.ObjImpl

Routed to by ImplMap entry (login, logins) -> LoginsImpl. Operates
on the per-user logins container at login-<actId>.

6.1 Authorization

  Verified-only AND owner-match - stricter than Quippin's
  per-user surfaces. Only the actId that owns the sub-host may
  read or modify its own login directory.

    public boolean hasRights(JsonMsg inMsg, Obj obj, DomatarMsgClient msgClient)
    {
      if (!Auth.isVerified(inMsg)) return false;
      Context ctx = inMsg.getContext();
      DomId   dst = inMsg.getDstId();
      return ctx.actId != null && ctx.actId.equals(dst.actId);
    }

  This is the policy other per-user surfaces SHOULD also adopt
  (FollowsImpl etc. have it as an open TODO); the Login app
  enforces it from day one because the data is more sensitive.

6.2 Operations on login-<actId>

  ListLogins(opr, inMsg, outMsg)
      Read the prefix "login-" under (login-<actId>, login, <actId>),
      return [{UsrId, UsrName, AppId, AddedAt, IsRoot}, ...].

  RecordLogin(opr, inMsg, outMsg)
      Write a "login-<usrId>" row. Called as a side-effect of the
      central-host-side AddAct in PART 7, NOT directly from the
      browser.

  ForgetLogin(opr, inMsg, outMsg)
      Delete a "login-<usrId>" row. Called as a side-effect of
      DeleteAct in PART 7.

  UpdateLoginRow(opr, inMsg, outMsg)
      Patch a "login-<usrId>" row's UsrName / UsrId fields. Called
      as a side-effect of UpdateAct in PART 7.

The "side-effect" pattern: the central host that owns the truth
(per-app act table) drives writes to the directory mirror on
login-<actId>. This guarantees that the directory cannot drift
out of sync with the act tables - if an UpdateAct succeeds at
the central host but the side-effect fails, the central host
reports the error and the user retries. PART 13 has a TODO for
making this two-phase / idempotent.

## PART 7 — CENTRAL HOST EXTENSIONS

The Login app introduces three new operations on every app's
ActManagerImpl. They are not Login-app-specific - they are
protocol additions to [Login protocol](Login-Protocol.md) that ANY app's central
host must implement to be manageable through the Login app.

  UpdateAct(actId, usrId, [newLocalname], [newUsrName])
      Changes <localname> and/or usrName on an existing act row.
      Authorization: hasRights = "verified AND ctx.actId ==
      target.actId" (the caller is verified AND is changing
      THEIR OWN row, not someone else's).
      Side-effect: post a Domatar message UpdateLoginRow to
      login-<actId> with the new field values.

  ChangePwd(actId, usrId, oldPwd, newPwd)
      Verifies oldPwd via the existing verifyLogin path; on match,
      replaces hashedPwd and salt; otherwise returns failure
      (NOT an Error, just LoggedIn=False semantics so the UI can
      display "wrong password").
      Authorization: same verified+owner-match check as UpdateAct.
      No directory side-effect (the directory does not store
      passwords).

  DeleteAct(actId, usrId)
      Removes the act row.
      Authorization: same verified+owner-match.
      Refuses if this would leave the actId with ZERO linked
      logins (the user would lock themselves out). The Login
      app should warn before sending; the central host enforces
      defensively.
      Side-effect: post ForgetLogin to login-<actId>.

These ops belong in [Login protocol](Login-Protocol.md) PART 11 as additions to
ActManagerImpl alongside addAct / verifyLogin / getAct / logout.
They do not change anything in the trust-boundary contract; they
just expose previously-implicit edits as first-class messages.

## PART 8 — OPERATIONS (worked examples)

8.1 First sign-up via the Login app

  1. Anonymous user visits /<context>/signup. Form fields:
     localname, usrName, password, [appId-selector default "login"].
  2. AccountsWui builds AddAct addressed to (<appId>, "act",
     "act@act", "actManager"). The wire Context is unverified.
  3. The <appId> central host's ActManagerImpl.addAct:
       - validates uniqueness of <localname>@<appId> (PART 4.4 of
         [Login protocol](Login-Protocol.md)).
       - mints a fingerprint actId ([Login protocol](Login-Protocol.md)
         PART 3.1 / PART 11.1).
       - persists the act row.
       - issues a session token.
       - dispatches RecordLogin to login-<actId> via
         HttpClient.send so the directory mirror is created with
         IsRoot=True.
       - replies { LoggedIn: "True", ActId, UsrId, UsrName, Token }.
  4. AccountsWui sets the cookies and redirects to
     /<context>/desktop ([Desktop](Desktop.md) PART 7).

8.2 Sign-in

  Inherits the existing login.html / remoteLogin.html flow
  unchanged - the Login app just owns the files now. With
  [Login protocol](Login-Protocol.md) PART 7.2 in place, sign-in is the SSO iframe
  on the verifying app's central host.

8.3 Add another app login

  Precondition: user is signed in. Context.actId is verified.

  1. User opens /<context>/account, clicks "Add login". Form
     fields: appId (dropdown), localname, usrName, password.
  2. AccountsWui builds AddAct addressed to (<appId>, "act",
     "act@act", "actManager") with body:
       ActId   = ctx.actId           (sourced from Context, NOT body)
       UsrId   = "<localname>@<appId>"
       UsrName, Pwd
  3. The <appId> central host's ActManagerImpl.addAct:
       - confirms ctx is verified (Auth.isVerified).
       - confirms <localname>@<appId> not yet taken on this host.
       - persists the act row REUSING the supplied actId
         ([Login protocol](Login-Protocol.md) PART 5).
       - dispatches RecordLogin to login-<actId>.
       - replies success.
  4. AccountsWui re-reads ListLogins and re-renders the manage page.

8.4 Rename usrId (<localname> only)

  1. User picks a row from the linked-logins list, clicks
     "Rename". Form field: newLocalname.
  2. AccountsWui builds UpdateAct addressed to the central host
     of the row's appId (NOT necessarily the user's root central
     host). Body:
       ActId         = ctx.actId
       UsrId         = current usrId
       NewLocalname  = newLocalname
  3. The central host's ActManagerImpl.updateAct:
       - verified+owner-match check.
       - validates newLocalname unique on this host.
       - rewrites usrId = "<newLocalname>@<appId>".
       - dispatches UpdateLoginRow to login-<actId> with new usrId.
       - replies success.

  Note that the @<appId> suffix is IMMUTABLE here: the central
  host receiving UpdateAct is the one that owns this act row;
  it will not re-route to a different host. Renames stay within
  one host's namespace.

8.5 Change usrName

  Same shape as 8.4, but only NewUsrName changes; usrId is
  untouched. The directory side-effect updates UsrName.

8.6 Change password

  1. User opens "Change password" form. Fields: oldPassword,
     newPassword.
  2. AccountsWui builds ChangePwd addressed to the central host of
     the row's appId. Body: ActId, UsrId, OldPwd, NewPwd.
  3. The central host verifies OldPwd (re-runs verifyLogin
     internally), updates the hashedPwd+salt fields. No directory
     side-effect.

8.7 Import existing login

  Use case: the user signed up directly with Quippin (not via
  the Login app), so login-<actId> never got a directory row for
  it. Now they want their Login-app manage page to show it.

  1. User opens "Import existing login" form. Fields: usrId,
     password.
  2. AccountsWui builds VerifyLogin addressed to the central host
     of the usrId's appId.
  3. The central host returns the actId on success. AccountsWui
     compares it to ctx.actId:
       - Match: write a directory row to login-<actId>. Done.
       - No match: refuse - "this is a different account". The
         user would have to sign out and sign in as that other
         actId to see it.

8.8 Remove a linked login

  1. User picks a row from the list, clicks "Remove".
  2. AccountsWui ListLogins to count rows. If this is the LAST
     login, refuse client-side with a hard warning. (The central
     host enforces defensively too.)
  3. AccountsWui builds DeleteAct addressed to that row's
     appId central host.
  4. The central host's ActManagerImpl.deleteAct:
       - verified+owner-match check.
       - performs ListLogins on login-<actId> to confirm at least
         one OTHER login exists. If this is the last, return
         failure.
       - deletes the act row.
       - dispatches ForgetLogin to login-<actId>.
       - replies success.

8.9 Sign out

  Sends Logout to the central host of the current session's
  verifier (the appId of the usrId carried in the session
  cookie). Per [Login protocol](Login-Protocol.md) PART 8 this is instant and
  network-wide (no per-prv cache to wait on).

## PART 9 — AUTHORIZATION SUMMARY

  Public                               Verified              Owner-match
  ------                               --------              -----------
  SignIn (login.html, iframe)          AddLogin              ListLogins
  SignUp                               RenameUsrId           RecordLogin
  ImportLogin (verifies its own creds) ChangeUsrName         UpdateLoginRow
                                       ChangePwd             ForgetLogin
                                       RemoveLogin
                                       SignOut

The Public column means "no Context.verified required". The
Verified column means "Context.verified must be true on the
trust boundary that handed us this request". The Owner-match
column adds "ctx.actId must equal the destination sub-host's
actId" - i.e. the user is touching their OWN data, not someone
else's.

## PART 10 — SOURCE LAYOUT

New files:

  src/main/webapp/login.html                  (already exists; Login
                                               app inherits ownership)
  src/main/webapp/remoteLogin.html            (already exists; same)
  src/main/webapp/signup.html                 (NEW; first-time sign-up
                                               form)
  src/main/webapp/account.html                (NEW; manage-my-logins
                                               page)
  src/main/webapp/icons/login.svg             (NEW; icon asset for
                                               Desktop's catalog)
  src/main/java/com/login/webui/AccountsWui.java
  src/main/java/com/login/objimpl/LoginsImpl.java

Edits to existing files:

  src/main/java/com/domatar/act/ActManagerImpl.java
      Add UpdateAct, ChangePwd, DeleteAct ops (PART 7). Each
      includes the side-effect dispatch to login-<actId>.

  src/main/java/com/domatar/util/ImplMap.java
      Register (login, logins) -> com.login.objimpl.LoginsImpl.

  src/main/webapp/WEB-INF/web.xml
      Add servlet mappings for /signup -> signup.html and
      /account -> account.html, paralleling the existing
      /news / /follows / etc. mappings.

- [Login protocol](Login-Protocol.md) — PART 5 mentions "Add app login UI lives in Desktop's settings"; point at the Login app. PART 11 / PART 13 should reference the Login app rather than Desktop for management surface.
- [Desktop](Desktop.md) — PART 11 TODO mentions "Add app login UI" as a future Desktop surface; that surface lives in the Login app. PART 8 (initial app catalog) includes a default Login icon, the same way it includes Quippin.

  mySQL/dump-*.sql (local sim seed)
      Add hst rows for "login" central host and per-user
      login-<actId> sub-hosts.

## PART 11 — POST-LOGIN INTEGRATION (Desktop catalog)

The Login app needs to be reachable from Desktop the same way
Quippin is. Desktop's lazy-bootstrap ([Desktop](Desktop.md) PART 6.2 /
PART 8) seeds new accounts with a Quippin icon; extend the seed
to also include a Login icon:

  Default Desktop catalog (per actId)
  -----------------------------------
    app-quippin   { DisplayName="Quippin",
                    IconPath="/quippin/icons/quippin.svg",
                    LaunchPath="/quippin/news",     Position="1" }
    app-login     { DisplayName="Account",
                    IconPath="/login/icons/login.svg",
                    LaunchPath="/login/account",    Position="2" }

Display name "Account" rather than "Login" because by the time the
icon is reachable the user IS logged in - the icon's job is to take
them to the manage page, not back to a sign-in form.

## PART 12 — LOCAL SIMULATION

The Login app needs a "login" central host in the directory, the
same way [Login protocol](Login-Protocol.md) PART 12 needs a "quippin" central host.
The simplest answer is the same answer: multi-alias tomcat1.

  docker-compose.yml, tomcat1's networks block:

    domatar_net:
      aliases:
        - domatar     (directory)
        - quippin     (Quippin central host)
        - login       (Login app central host)

  directory seed:

    INSERT INTO hst VALUES
      ('login', 'tomcat1:8080', 'prv1', 1, 0);

  per-user sub-host seed (one row per seeded user):

    INSERT INTO hst VALUES
      ('login-dave@quippin',  'localhost', 'prv1', 1, 0),
      ('login-micha@quippin', 'localhost', 'prv2', 1, 0);

The HttpClient self-dispatch shortcut ([Domatar](../Domatar.md) PART 8) keeps this
multi-aliasing safe: any in-process call from tomcat1 to the
"login" hstId resolves to prvId=prv1 = DOMATAR_HSTID, so sendLocal
fires, no recursive HTTP.

## PART 13 — TODO

  - Two-phase / idempotent side-effects. PART 6.2 and PART 7
    rely on the central host dispatching a follow-up message to
    login-<actId> after every AddAct/UpdateAct/DeleteAct. If the
    side-effect fails, the directory mirror drifts. Make the
    side-effect idempotent (re-driveable from a queue) and
    surface drift in the manage-page UI ("this row exists at
    Quippin's central host but is missing from your directory -
    import?").
  - Cross-actId proof for "Add login" when the new login already
    exists at the target central host with a DIFFERENT actId
    (i.e. someone else's account). Today addAct refuses on
    uniqueness; the user just gets "usrId already taken". A
    nicer UX would be "this usrId is taken; if it's yours, sign
    in to it via Import" but that requires the central host to
    distinguish the two cases without leaking whose account it
    is. Out of v1.
  - Security on ChangePwd: rate-limit, require reverify-with-2FA
    if the app supports it, log the change to an audit trail.
    Out of v1.
  - "Sign out everywhere now" surface: the no-cache policy
    already gives near-instant sign-out network-wide, but a
    user who panics about a lost device may want a button that
    explicitly invalidates ALL their tokens at the central host
    in one shot, not just the current session. One-line addition
    to Logout op once central-host session storage exists.
  - Profile fields beyond usrName: avatars, pronouns, bio,
    contact info. These are per-usrId properties on the act row.
    Out of v1 - keep the manage page small.
  - First-class app-catalog discovery: today the Login app's
    "Add login" form has a hard-coded list of available appIds.
    Future: read the directory of installed apps ([Domatar](../Domatar.md)
    PART 11 TODO) and let the user pick from any of them.
