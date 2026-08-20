# LLM-Oriented Message Operations for Each Domatar App

PURPOSE
-------
When the AI Agent calls useApp(appName), it fetches the app's (appName, app) class
descriptor and registers its Msgs field as typed tools. This spec defines the
LLM-native operations to expose for each app.

All operations live on the app entry-point object (e.g. app-spreadsheet).
Each app needs:
  1. A (appname, app) class descriptor with a Msgs field (installed via ClsInstall).
  2. A handler class (AppNameImpl) for the app-<name> object that implements the ops.

Msgs format (plain text):
  "Op1(Parm1,Parm2) -> {ReturnShape}; Op2() -> {ReturnShape}"
SideEffect string:
  "Op1:None, Op2:Write, Op3:Delete"   (None maps to "Read")

Implementation status:

- `[DONE]` — class descriptor + handler exist and work
- `[CLS]` — needs class descriptor installed (handler exists under a different class)
- `[NEW]` — needs both class descriptor and new handler on app entry-point

# SPREADSHEET (spreadsheet)

Status: [DONE]

Description:
  Spreadsheet app entry point. Use these LLM-native operations to query and
  update the user's spreadsheets by name.

Msgs:
  GetSpreadsheets() -> {Sheets:[{Name,Cols,Rows,CreatedAt}]};
  GetSpreadsheet(Name) -> {Name,Cols,Rows,ColLabels,Cells:[{CellRef,Raw,Value}]};
  SetCell(Name,CellRef,Raw) -> {CellRef,Value};
  DeleteSpreadsheet(Name) -> {Deleted}

SideEffect:
  GetSpreadsheets:None, GetSpreadsheet:None, SetCell:Write, DeleteSpreadsheet:Delete

Notes:
  SpreadsheetImpl already handles class (spreadsheet, app) on app-spreadsheet.
  GetSpreadsheet returns ALL cells with raw formula and computed value.
  Prefer GetSpreadsheets first, then GetSpreadsheet(Name) to retrieve data.

# AI AGENT (aiagent)

Status: [DONE]

Description:
  AI Agent app entry point. Exposes the user's conversation history and
  lets you query past conversations by title.

Msgs:
  GetConversations() -> {Conversations:[{Title,ConvId,Mode,MessageCount,UpdatedAt}]};
  GetConversation(Title) -> {Title,ConvId,Mode,Messages:[{Role,Text,Time,ToolName,ToolResult}]}

SideEffect:
  GetConversations:None, GetConversation:None

Notes:
  GetConversations delegates to ConversationsImpl.ListConversations (aiagent, conversations).
  GetConversation finds the conv by title (case-insensitive) then returns its full
  message thread including tool calls so the LLM can reason about prior agent sessions.
  Implementation: create AiagentAppImpl handling (aiagent, app) on app-aiagent.

# BOOKSTORE (bookstore)

Status: [DONE]

Description:
  Bookstore app entry point. Browse books for sale, manage your library of
  purchased books, and view the global catalog.

Msgs:
  GetForSale() -> {Listings:[{ListingId,Title,Author,Price,Condition,ISBN,Description}]};
  GetLibrary() -> {Books:[{BookId,Title,Author,ISBN,Price,Condition,Description}]};
  GetCart() -> {Items:[{ListingDomId,Title,Author,Price,Condition,SellerActId,SellerName,Available}]};
  GetCatalog() -> {Listings:[{ListingId,Title,Author,Price,SellerName,SellerActId,Condition}]}

SideEffect:
  GetForSale:None, GetLibrary:None, GetCart:None, GetCatalog:None

Notes:
  GetForSale delegates to ForSaleImpl.GetForSale (bookstore, forsale).
  GetLibrary delegates to LibraryImpl.GetLibrary (bookstore, library).
  GetCatalog delegates to CatalogImpl.GetCatalog (bookstore, catalog) — reads
    the cross-provider central catalog; may include listings from other users.
  Implementation: create BookstoreAppImpl handling (bookstore, app) on app-bookstore,
    delegating to the sub-objects (forsale, library, catalog) already at known DomIds.

# QUIPPIN (quippin)

Status: [DONE]

Description:
  Quippin microblog entry point. Read the user's own posts and their news
  feed from followed users.

Msgs:
  GetQuips() -> {Quips:[{QuipId,Text,Time,Likes}]};
  GetFeed() -> {Quips:[{QuipId,Text,Time,Author,Likes}]};
  GetQuip(QuipId,ActId) -> {QuipId,ActId,Text,Time,Likes,ParentId,ReQuipId};
  GetReplies(QuipId,ActId) -> {Quips:[{QuipId,Text,Time,Likes}]};
  GetNews(ActIds) -> {Quips:[{QuipId,Text,Time,ActId,Likes}]};
  GetFollows() -> {Follows:[{ActId,UsrId,UsrName}]};
  IsFollowed(ActId) -> {ActId,IsFollowed};
  FindUsers(Query) -> {Users:[{ActId,UsrId,UsrName}]};
  GetBans() -> {BannedActs:[{ActId,UsrName,UsrId}]};
  GetLogs() -> {Logs:[{LogType,Operation,Time,UsrName,UsrId}]};
  Follow(ActId) -> {Follow,ActId,UsrName};
  Unfollow(ActId) -> {Follow,ActId};
  PostQuip(Text) -> {QuipId}

SideEffect:
  GetQuips:None, GetFeed:None, GetQuip:None, GetReplies:None, GetNews:None,
  GetFollows:None, IsFollowed:None, FindUsers:None, GetBans:None, GetLogs:None,
  Follow:Write, Unfollow:Write, PostQuip:Write

Notes:
  GetQuip(QuipId,ActId): ActId defaults to caller if omitted; reads quip attrs directly.
  GetReplies(QuipId,ActId): reads child-quip links from the given quip.
  GetNews(ActIds): comma-separated actId list; last-24h quips, max 50 per act.
  IsFollowed(ActId): checks whether the caller has a follow row for that ActId.
  GetBans: reads the caller's bans container (banAct objects).
  GetLogs: reads the caller's logs container (last 200 entries).
  Follow / Unfollow mirror FollowsImpl.follow / unfollow logic.
  PostQuip adds a quip to the user's quips container.
  Implementation: QuippinAppImpl handling (quippin, app) on app-quippin.

# APP STORE (appstore)

Status: [DONE]

Description:
  App Store entry point. List which apps are installed and which are
  available in the catalog.

Msgs:
  GetApps() -> {InstalledApps:[{AppId,AppName,AppDesc,IsCore}],AvailableApps:[{AppId,AppName,AppDesc}]}

SideEffect:
  GetApps:None

Notes:
  Delegates to AppstoreImpl.GetApps (appstore, home) at the home-<actId> sub-object.
  The LLM can answer "what apps do I have installed?" and "what apps are available?"
  with a single call. Write ops (install/uninstall) are intentionally omitted —
  they require user confirmation and are available in Agent mode via Agent (read/write).
  Implementation: create AppstoreAppImpl handling (appstore, app) on app-appstore,
    delegating to the existing home object.

# MONEY (money)

Status: [DONE]

Description:
  Money app entry point. View banking role, list accounts with current
  balances, and transfer funds.

Msgs:
  GetProfile() -> {Role,HasBank};
  GetMyAccounts() -> {Accounts:[{BankName,BankActId,Balance,AccountDomId}]};
  GetAccount(AccountDomId) -> {CustomerActId,CustomerName,Balance,BankActId,BankName};
  GetBank() -> {Name,TotalFunds,AvailableFunds};
  GetAccounts() -> {Accounts:[{AccountDomId,CustomerActId,CustomerName,Balance}]};
  Transfer(Amount,ToActId) -> {Balance}

SideEffect:
  GetProfile:None, GetMyAccounts:None, GetAccount:None, GetBank:None, GetAccounts:None,
  Transfer:Write

Notes:
  GetProfile delegates to ProfileImpl.GetProfile (money, profile).
  GetMyAccounts delegates to MyAccountsImpl.GetMyAccounts (money, myaccounts).
    Performs cross-host fetches to gather live balances from each linked account.
  Transfer delegates to AccountImpl.Transfer (money, account) — requires the
    caller to have an account at a bank. ToActId is the recipient's actId.
  Implementation: create MoneyAppImpl handling (money, app) on app-money,
    delegating to profile and myaccounts sub-objects at known DomIds.

# DESKTOP (desktop)

Status: [DONE]

Description:
  Desktop home screen. List the apps installed as tiles.

Msgs:
  GetApps() -> {Apps:[{AppId,DisplayName,LaunchPath,Position}]}

SideEffect:
  GetApps:None

Notes:
  AppsImpl.GetApps (desktop, apps) already implements this on the apps sub-object.
  The existing (desktop, app) class descriptor installs under class (desktop, app)
  but exposes only GetObj — update it to include GetApps.
  For LLM queries about "what apps do I have?", App Store's GetApps is richer.
  Desktop's GetApps is most useful for UI layout / position queries.

# NAVIGATOR (navigator)

Status: [CLS] — app entry-point is (domatar, app), no custom ops needed

Description:
  Navigator entry point. The Navigator root is already the starting point
  for listApps — no additional LLM-native ops are needed at this level.
  GetObj and GetLnks on the root provide app discovery natively.

Msgs: (none — the listApps tool internally calls GetLnks on the Navigator root)

Notes:
  The dynamic toolset's listApps already calls GetLnks on the Navigator root,
  so adding Navigator LLM ops would be redundant. Skip.

# LOGIN (login)

Status: [NEW] — low priority

Description:
  Login app entry point. Shows the user's linked login identities (usrId/usrName
  per app).

Msgs:
  GetLogins() -> {Logins:[{UsrId,UsrName,AppId,AddedAt,IsRoot}]}

SideEffect:
  GetLogins:None

Notes:
  Delegates to LoginsImpl.ListLogins (login, logins).
  Useful for answering "what are my login names / handles?".
  Low priority for initial implementation — rarely useful in agent queries.

# IMPLEMENTATION PRIORITY

Implement in this order (highest user value first):

  1. AI Agent   — users often ask "how many conversations do I have?"
  2. Bookstore  — "show me my books for sale" / "what's in my library?"
  3. Quippin    — "show me my recent posts"
  4. Money      — "what are my account balances?"
  5. App Store  — "what apps do I have installed?"
  6. Desktop    — update existing appCls with GetApps Msgs
  7. Login      — "what are my login names?"

Each implementation follows this pattern (see SpreadsheetInstall.java for reference):

  a. Create a new <App>AppImpl.java handling class (<appname>, app) with the ops above.
  b. In <App>Install.java, call ClsInstall.upsertClsObj on the app entry-point DomId
     with the Msgs and SideEffect strings from this spec.
  c. Rebuild and redeploy.
