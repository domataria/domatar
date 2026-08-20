# MONEY APP — SPECIFICATION

A simulated banking application built on the Domatar platform.  Users adopt
one of two roles: Bank or Customer.  Banks hold pools of funds and manage
accounts; customers hold accounts at one or more banks and can pay each other
or move money between banks.

The defining architectural invariant is:

  Account objects always live on the bank's host, not the customer's host —
  even though the actId embedded in the account's DomId is the customer's
  actId.  The bank controls the object's physical location (hstId); the
  customer is its logical owner (actId).

The Money app runs on prv2 (tomcat2 / db2).  There is no shared central
catalog; all data is distributed across per-user sub-hosts.

## PART 1 — OVERVIEW

1.1  Goals
----------
  * A user chooses the role Bank or Customer after installation.  The role is
    stored in a lightweight profile object and can be changed at any time
    (this is a simulation; real-world role separation is not enforced).

  * A bank user:
      - Declares a pool of total funds.
      - Creates accounts for customers (with an initial balance drawn from the
        pool, or zero).
      - Can deposit into or withdraw from any customer account.

  * A customer user:
      - Holds accounts at one or more banks.  Each account is physically on
        the bank's host.
      - Can pay another customer who banks at the same bank.  If the payee
        does not yet have an account at that bank the bank creates one
        automatically (with the received amount as the opening balance).
      - Can transfer money from their account at one bank to their account at
        another bank (inter-bank transfer).

  * Money does not leave the Domatar network: it is always an attribute on an
    account object.  An inter-bank transfer deducts from one account and
    credits another via a cross-host message.

1.2  Non-goals (deferred)
--------------------------
  * Interest, fees, or time-based calculations.
  * Multi-currency.
  * Shared/joint accounts.
  * Audit log / transaction history (may be added as a future pass).
  * Real authentication between banks (any authenticated Domatar user may
    call Credit on any bank — see Part 7).

## PART 2 — PROVIDER AND HOSTING

2.1  Central host (system account only)
----------------------------------------
  hstId  : money
  prvId  : prv2
  domain : domatar.avatarvia.com
  address: tomcat2:8080

  The central host holds only the system account.  All user data lives on
  per-user sub-hosts (see 2.2).

  tomcat2 must expose the alias "money" on domatar_net:

    tomcat2:
      networks:
        domatar_net:
          aliases:
            - tomcat2
            - bookstore       (already present)
            - spreadsheet     (already present)
            - money           ← add this

2.2  Per-user sub-hosts
-----------------------
  Every user who installs Money gets a personal sub-host:

    hstId : money-<actId>
    prvId : <the prv the user is on when they install>
    domain: same as the user's prv (e.g. domatar.quippin.com for prv1)

  The sub-host is created by MoneyInstall (see Part 5).

  For bank users, all customer accounts will also live on this same sub-host,
  even though those accounts belong to other users' actIds.

2.3  Nginx routing
------------------
  domatar.avatarvia.com already routes to tomcat2:8080 (added for Bookstore).
  No additional Nginx block is required.

2.4  System account
-------------------
  actId  : money@money
  usrId  : money@money
  usrName: Money
  password: "123"  (SHA-1 hash: GBp05MC8NxDHPJAUdVySNhkRkjx — same as others)

## PART 3 — DATA MODEL

3.1  Object classes
-------------------

  (money, profile)
    One per user.  Stores the chosen role.
    ObjId : profile
    HstId : money-<actId>
    ActId : <actId>                 (owner's actId)
    Attrs :
      Role : "Bank" | "Customer" | ""   (empty = not yet chosen)

  (money, bank)
    Created when a user sets their role to Bank.  Represents the bank itself.
    ObjId : bank
    HstId : money-<bankActId>
    ActId : <bankActId>
    Attrs :
      Name           : string   (bank display name)
      TotalFunds     : string   (decimal, e.g. "100000.00")
      AvailableFunds : string   (decimal; TotalFunds minus sum of all balances)

  (money, accounts)
    Container linking to all customer accounts at this bank.
    One per bank.
    ObjId : accounts
    HstId : money-<bankActId>
    ActId : <bankActId>
    Attrs : {}

  (money, account)   ← THE CRITICAL OBJECT
    One per (customer, bank) pair.
    ObjId : acct-<customerActId>          e.g. "acct-dave@quippin"
    HstId : money-<bankActId>             ← lives on THE BANK'S HOST
    ActId : <customerActId>               ← but belongs to THE CUSTOMER
    Attrs :
      CustomerActId : string   (same as the ActId — stored explicitly for
                                convenience in listings)
      CustomerName  : string   (display name of the customer)
      Balance       : string   (decimal, non-negative, e.g. "250.00")
      BankActId     : string   (the bank's actId — for back-reference)
      BankName      : string   (display name of the bank)

    The DomId is therefore:
      money-<bankActId>.money.<customerActId>.acct-<customerActId>

    Security implications:
      Standard Domatar auth passes if req.actId == obj.actId, which means the
      customer naturally has read access.  AccountImpl.hasRights additionally
      grants full access to the bank user by deriving the bank's actId from
      the hstId: bankActId = obj.getDomId().getHstId().substring("money-".length())
      and checking req.actId == bankActId.  See Part 7 for the full table.

  (money, myaccounts)
    One per user.  Container in the customer's own host holding links to their
    accounts across all banks.  Present for every user (even bank users may
    personally hold accounts elsewhere).
    ObjId : myaccounts
    HstId : money-<actId>
    ActId : <actId>
    Attrs : {}

3.2  Link types
---------------

  accounts  →  account
    Tag    : (money, account)
    Val    : <customerActId>       for fast lookup by customer
    SeqNum : <creation timestamp millis>

  myaccounts  →  account   (cross-host link; dst is on the bank's sub-host)
    Tag    : (money, account)
    Val    : <bankName>            for display
    SeqNum : <creation timestamp millis>

  Navigator links — see Part 4.

3.3  Decimal arithmetic
-----------------------
  Balances and fund totals are stored as decimal strings (e.g. "1234.56").
  Server-side arithmetic uses Java's BigDecimal to avoid floating-point error.
  Negative balances are never allowed; operations that would produce one are
  rejected with an error.

## PART 4 — NAVIGATOR INTEGRATION

seqNum = 8  (after Spreadsheet at 7).

MoneyInstall always adds the following nodes:

  root  →  [navigator/app]  app-money                       seqNum=8
  app-money  →  [navigator/container]  myaccounts           seqNum=1

When the user sets their role to Bank, the following are added:

  app-money  →  [navigator/container]  bank                 seqNum=2
  bank       →  [navigator/container]  accounts             seqNum=1

When the user sets their role to Customer, no additional Navigator links are
needed at setup time.  Each time the bank creates an account, it sends a
RegisterAccount message to the customer's myaccounts object, which adds a
cross-host link there.

Icon class hints:
  app-money node     : (navigator, app)
  bank node          : (money, bank)
  accounts container : (money, accounts)
  account node       : (money, account)
  myaccounts         : (money, myaccounts)

Icons for the money cls sub-directory (icons/cls/money/) should be created:
  bank.svg        — a small building or vault glyph (blue or gold)
  accounts.svg    — a stack of account cards
  account.svg     — a single account card with a balance indicator
  myaccounts.svg  — a wallet or accounts overview glyph

## PART 5 — INSTALL ROUTINE  (MoneyInstall.java)

Package: com.money.install

Static method:
  public static void install(String actId,
                             String usrId,
                             String usrName,
                             String domain,
                             String prvId,
                             DomatarMsgClient msgClient) throws DomatarException

Steps (all idempotent via addObjIfMissing / addLnkIfMissing):

  1. Register sub-host:
       HstDb.addHstIfMissing("money-" + actId, prvId, domain)

  2. Create profile object on money-<actId>:
       (money, profile)  ObjId=profile  Name="Profile"  Desc="Money role"
       Attrs: { Role: "" }

  3. Create myaccounts container on money-<actId>:
       (money, myaccounts)  ObjId=myaccounts  Name="My Accounts"
                             Desc="Your accounts at all banks"

  4. Create app-money on navigator-<actId>:
       (navigator, app)  ObjId=app-money  Name="Money"
                         Desc="Banking and payments"

  5. Add root → app-money link          (navigator/app,       seqNum=8)

  6. Add app-money → myaccounts link    (navigator/container, seqNum=1)

ActManagerImpl changes:
  Call MoneyInstall.install(actId, usrId, usrName, domain, prvId, sideClient)
  from addAct(), after SpreadsheetInstall.

## PART 6 — OPERATIONS

6.1  ProfileImpl   (money, profile)
------------------------------------
hasRights: owner only  (Auth.isVerified && req.actId == obj.actId)

  GetProfile
    In:  (none)
    Out: { Role }

  SetRole
    In:  Role  ("Bank" | "Customer" | "")
    Out: { Role }

    Steps:
      a. Update profile.Role in the DB.
      b. If Role == "Bank" and (money, bank) does not yet exist:
           i.  Require BankName and TotalFunds params (passed in the same
               message).
           ii. Create (money, bank) with AvailableFunds = TotalFunds.
           iii.Create (money, accounts).
           iv. Add Navigator links: app-money → bank (seqNum=2)
                                     bank → accounts  (seqNum=1)
      c. If Role == "Customer": no further action (myaccounts already exists
         from install).
      d. Return { Role: <new value> }.

6.2  BankImpl   (money, bank)
------------------------------
hasRights: bank user only  (Auth.isVerified && req.actId == bankActId)
           where bankActId is derived from obj.domId.getActId()

  GetBank
    In:  (none)
    Out: { Name, TotalFunds, AvailableFunds }

  SetFunds
    In:  TotalFunds (decimal string)
    Out: { TotalFunds, AvailableFunds }

    Validation:
      newTotal >= (TotalFunds - AvailableFunds)  [cannot lower below
      what is already distributed to accounts]
    Steps:
      distributed = TotalFunds - AvailableFunds
      new AvailableFunds = newTotal - distributed
      Save both.

6.3  AccountsImpl   (money, accounts)
---------------------------------------
hasRights (general): bank user only, EXCEPT for Credit (see Credit below).

  GetAccounts
    In:  (none)
    Out: { Accounts: [ {CustomerActId, CustomerName, Balance}, … ] }

    Steps:
      Read all links from accounts with Tag=(money, account), sorted by
      SeqNum ascending.  For each link fetch the account object.

  CreateAccount
    In:  CustomerActId, CustomerName, InitialBalance (decimal string, default "0.00")
    Out: { AccountDomId }

    Validation:
      InitialBalance >= 0
      InitialBalance <= bank.AvailableFunds
      No existing account for CustomerActId

    Steps:
      a. Deduct InitialBalance from bank.AvailableFunds.
      b. Create (money, account) on money-<bankActId>:
           hstId  = money-<bankActId>
           appId  = money
           actId  = <CustomerActId>         ← customer's actId
           objId  = acct-<CustomerActId>
         Attrs: CustomerActId, CustomerName, Balance=InitialBalance,
                BankActId=<bankActId>, BankName=<bank.Name>
      c. LnkDb.addLnk(accounts → account, Tag=(money,account),
                       Val=CustomerActId, SeqNum=now)
      d. Send RegisterAccount to the customer's myaccounts:
           dst = money-<CustomerActId>.money.<CustomerActId>.myaccounts
           Params: AccountDomId=<full DomId string>, BankName=<bank.Name>
         (fire-and-forget via msgClient.send; ignore errors — the customer
          may not have Money installed yet, or may install it later)
      e. Return { AccountDomId }.

  Credit   (called cross-host by another bank performing a BankTransfer)
    In:  CustomerActId, CustomerName, Amount (decimal string > "0.00")
    Out: { Balance }

    hasRights for Credit only: any authenticated Domatar user may call
    Credit on this accounts object.  (The bank validates Amount > 0 and
    delegates trust to Domatar authentication.)

    Steps:
      a. Find or create (money, account) for CustomerActId:
           If not found: create with Balance="0.00", CustomerName as supplied.
                         Add link accounts → account.
                         Send RegisterAccount to customer's myaccounts.
      b. Add Amount to account.Balance (BigDecimal).
      c. Return { Balance: <new balance> }.

    Note: Credit does NOT touch bank.AvailableFunds or bank.TotalFunds.
    The funds arrive from outside this bank's ledger.

6.4  AccountImpl   (money, account)
-------------------------------------
hasRights:
  Granted if any of:
    a. req.actId == obj.domId.getActId()   (customer — natural owner)
    b. req.actId == obj.domId.getHstId().substring("money-".length())
                                           (bank — derived from hstId)
  In both cases Auth.isVerified(inMsg) must also be true.

  GetAccount
    In:  (none)
    Out: { CustomerActId, CustomerName, Balance, BankActId, BankName }
    Callable by: bank or owning customer.

  Deposit   (bank → customer account)
    In:  Amount (decimal > "0")
    Out: { Balance }
    Callable by: bank only.
    Steps:
      account.Balance += Amount
      bank.AvailableFunds -= Amount
      (Both objects are on the same host; both DB writes happen locally.)

  Withdraw   (bank removes money from customer account, e.g. cash withdrawal)
    In:  Amount (decimal > "0")
    Out: { Balance }
    Callable by: bank only.
    Validation: Amount <= account.Balance
    Steps:
      account.Balance -= Amount
      bank.AvailableFunds += Amount

  Transfer   (customer pays another customer at the same bank)
    In:  ToCustomerActId, ToCustomerName, Amount (decimal > "0"),
         Description (optional string)
    Out: { FromBalance, ToBalance }
    Callable by: owning customer only.

    Steps:
      a. Validate account.Balance >= Amount.
      b. Locate destination account:
           dstObjId = "acct-" + ToCustomerActId
           dstDomId = DomId(money-<bankActId>, money, <ToCustomerActId>, dstObjId)
           dstObj   = ObjDb.getObj(dstDomId)
           If dstObj == null:
             Create destination account with Balance="0.00" (no bank funds
             deducted — the balance will come from step d).
             Add link accounts → dstAccount.
             Send RegisterAccount to ToCustomerActId's myaccounts.
      c. Deduct Amount from this account.Balance.
      d. Add Amount to destination account.Balance.
      e. bank.AvailableFunds is unchanged (internal transfer).
      f. Return { FromBalance, ToBalance }.

  BankTransfer   (customer moves money from this bank to their account at
                  another bank)
    In:  DestBankActId, Amount (decimal > "0")
    Out: { Balance }
    Callable by: owning customer only.

    Steps:
      a. Validate account.Balance >= Amount.
      b. Deduct Amount from account.Balance.
      c. Add Amount to bank.AvailableFunds  (funds leave this bank's ledger).
      d. Send Credit message to the destination bank's accounts object:
           dst = money-<DestBankActId>.money.<DestBankActId>.accounts
           Operation = Credit
           Params: CustomerActId=<this account's actId>,
                   CustomerName=<account.CustomerName>,
                   Amount=<Amount>
         The message is sent via msgClient (cross-host if DestBankActId is on
         a different provider).  Errors are returned to the caller.
      e. Return { Balance: <new balance after deduction> }.

6.5  MyAccountsImpl   (money, myaccounts)
------------------------------------------
hasRights (GetMyAccounts): owner only.
hasRights (RegisterAccount): any authenticated user.

  GetMyAccounts
    In:  (none)
    Out: { Accounts: [ {AccountDomId, BankName, Balance}, … ] }

    Steps:
      Read all links from myaccounts with Tag=(money, account).
      For each link, fetch the account object (cross-host call if needed).
      Return AccountDomId, BankName (from link Val or account attrs), Balance.

  RegisterAccount   (called by a bank after creating an account for this user)
    In:  AccountDomId (full DomId string), BankName
    Out: { Registered: "True" }

    Steps:
      Parse AccountDomId string → DomId.
      If a link to this DomId does not already exist in myaccounts:
        LnkDb.addLnk(myaccounts → accountDomId,
                      Tag=(money, account), Val=BankName, SeqNum=now)

## PART 7 — AUTHORIZATION SUMMARY

  Object              Operation          Who may call
  ------------------  -----------------  -----------------------------------
  (money, profile)    GetProfile         owner
  (money, profile)    SetRole            owner
  (money, bank)       GetBank            bank user
  (money, bank)       SetFunds           bank user
  (money, accounts)   GetAccounts        bank user
  (money, accounts)   CreateAccount      bank user
  (money, accounts)   Credit             any authenticated Domatar user
  (money, account)    GetAccount         bank user OR owning customer
  (money, account)    Deposit            bank user
  (money, account)    Withdraw           bank user
  (money, account)    Transfer           owning customer
  (money, account)    BankTransfer       owning customer
  (money, myaccounts) GetMyAccounts      owner
  (money, myaccounts) RegisterAccount    any authenticated Domatar user

  "bank user" = Auth.isVerified(inMsg) && req.actId == bankActId
  "owning customer" = Auth.isVerified(inMsg) && req.actId == account.actId
  "owner" = Auth.isVerified(inMsg) && req.actId == obj.actId

## PART 8 — FRONTEND UI

8.1  money.html   (entry point / role selection)
-------------------------------------------------
  URL  : /domatar/money.html
  Auth : must be logged in

  Layout:
    - Loads the user's profile via GetProfile.
    - If Role == "" (unset): shows two prominent buttons:
        "I am a Bank"     → opens a sub-form asking for Bank Name and
                            Total Funds, then calls SetRole("Bank").
        "I am a Customer" → calls SetRole("Customer") immediately.
    - If Role == "Bank":
        Shows "You are operating as a bank."
        Link to bank.html (bank management dashboard).
        Small note: "Switch role" → allows resetting to Customer.
    - If Role == "Customer":
        Shows "You are a customer."
        Link to accounts.html (customer accounts dashboard).
        Small note: "Switch role" → allows resetting to Bank.

8.2  bank.html   (bank management dashboard)
---------------------------------------------
  URL  : /domatar/bank.html
  Auth : must be logged in; redirects to money.html if role ≠ Bank

  Layout:
    - Header: bank name, TotalFunds, AvailableFunds (loaded via GetBank).
    - "Set Funds" button: inline form to update TotalFunds (calls SetFunds).
    - Customer accounts table:
        Columns: Customer Name | Customer ActId | Balance | Actions
        Actions per row: [Deposit] [Withdraw]
          Each opens a small inline amount prompt, then calls Deposit/Withdraw.
    - "Create Account" button: modal asking for:
        Customer ActId  (text)
        Customer Name   (text)
        Initial Balance (number, default 0)
      On confirm: calls CreateAccount.

  AJAX calls:
    GET  /domatar/MoneyBankWui?Action=GetBank
    GET  /domatar/MoneyAccountsWui?Action=GetAccounts
    POST /domatar/MoneyBankWui          Action=SetFunds     TotalFunds=…
    POST /domatar/MoneyAccountsWui      Action=CreateAccount
                                        CustomerActId=… CustomerName=…
                                        InitialBalance=…
    POST /domatar/MoneyAccountWui       Action=Deposit      AccountDomId=…
                                        Amount=…
    POST /domatar/MoneyAccountWui       Action=Withdraw     AccountDomId=…
                                        Amount=…

8.3  accounts.html   (customer accounts dashboard)
---------------------------------------------------
  URL  : /domatar/accounts.html
  Auth : must be logged in; redirects to money.html if role ≠ Customer

  Layout:
    - List of the user's accounts (loaded via GetMyAccounts):
        Per account card: Bank Name, Balance.
        Expand → shows AccountDomId, BankActId.
    - Per account actions:
        [Pay]           → modal: To Customer ActId, To Customer Name, Amount,
                          Description.  Calls Transfer.
        [Bank Transfer] → modal: Destination Bank ActId, Amount.
                          Calls BankTransfer.

  AJAX calls:
    GET  /domatar/MoneyMyAccountsWui?Action=GetMyAccounts
    POST /domatar/MoneyAccountWui    Action=Transfer
                                     AccountDomId=… ToCustomerActId=…
                                     ToCustomerName=… Amount=… Description=…
    POST /domatar/MoneyAccountWui    Action=BankTransfer
                                     AccountDomId=… DestBankActId=… Amount=…

## PART 9 — WUI SERVLETS

IMPORTANT: WUIs must NOT call addClsId() in their outgoing messages.
(Calling addClsId causes the dispatcher to skip ObjDb.getObj, passing obj=null
to the handler.  Omitting it lets the dispatcher load the object normally.)

  MoneyProfileWui    @WebServlet("/MoneyProfileWui/*")
    Action=GetProfile  → dst = money-<actId>.money.<actId>.profile / GetProfile
    Action=SetRole     → dst = (same) / SetRole; Params: Role, [BankName, TotalFunds]

  MoneyBankWui       @WebServlet("/MoneyBankWui/*")
    Action=GetBank     → dst = money-<actId>.money.<actId>.bank / GetBank
    Action=SetFunds    → dst = (same) / SetFunds; Params: TotalFunds

  MoneyAccountsWui   @WebServlet("/MoneyAccountsWui/*")
    Action=GetAccounts   → dst = money-<actId>.money.<actId>.accounts / GetAccounts
    Action=CreateAccount → dst = (same) / CreateAccount
                           Params: CustomerActId, CustomerName, InitialBalance
    Action=Credit        → dst = money-<destBankActId>.money.<destBankActId>.accounts
                           Params: CustomerActId, CustomerName, Amount
                           (called internally by AccountImpl.BankTransfer, not by UI)

  MoneyAccountWui    @WebServlet("/MoneyAccountWui/*")
    Reads AccountDomId param from request; parses it into a DomId.
    Action=GetAccount    → dst = <AccountDomId> / GetAccount
    Action=Deposit       → dst = (same) / Deposit;   Params: Amount
    Action=Withdraw      → dst = (same) / Withdraw;  Params: Amount
    Action=Transfer      → dst = (same) / Transfer;
                           Params: ToCustomerActId, ToCustomerName, Amount,
                                   Description
    Action=BankTransfer  → dst = (same) / BankTransfer;
                           Params: DestBankActId, Amount

  MoneyMyAccountsWui @WebServlet("/MoneyMyAccountsWui/*")
    Action=GetMyAccounts    → dst = money-<actId>.money.<actId>.myaccounts
                              / GetMyAccounts
    Action=RegisterAccount  → dst = (same) / RegisterAccount
                              Params: AccountDomId, BankName
                              (called internally by AccountsImpl, not by UI)

## PART 10 — IMPLMAP ENTRIES

  In com.domatar.core.ImplMap:

    import com.money.objimpl.ProfileImpl;
    import com.money.objimpl.BankImpl;
    import com.money.objimpl.AccountsImpl;
    import com.money.objimpl.AccountImpl;
    import com.money.objimpl.MyAccountsImpl;

    put("money", "profile",    new ProfileImpl());
    put("money", "bank",       new BankImpl());
    put("money", "accounts",   new AccountsImpl());
    put("money", "account",    new AccountImpl());
    put("money", "myaccounts", new MyAccountsImpl());

## PART 11 — DATABASE SEEDING

File: mySQL/dump-2024-01-24-money.sql

  1. System account in the act table:
       actId   = "money@money"
       usrId   = "money@money"
       usrName = "Money"
       password hash = GBp05MC8NxDHPJAUdVySNhkRkjx

  2. Central HST row:
       hstId  = "money"
       prvId  = "prv2"
       domain = "money:8080"   (Docker alias)

  3. Per-user sub-HST rows for dave and micha:
       ("money-dave@quippin",  "prv1", "tomcat1:8080")
       ("money-micha@quippin", "prv2", "tomcat2:8080")

  4. Profile objects for dave and micha:
       ("money-dave@quippin",  "money", "dave@quippin",  "profile",
        "money", "profile", "Profile", "Money role", '{"Role":""}')
       ("money-micha@quippin", "money", "micha@quippin", "profile",
        "money", "profile", "Profile", "Money role", '{"Role":""}')

  5. MyAccounts container for dave and micha:
       ("money-dave@quippin",  "money", "dave@quippin",  "myaccounts",
        "money", "myaccounts", "My Accounts", "Your accounts at all banks", '{}')
       ("money-micha@quippin", "money", "micha@quippin", "myaccounts",
        "money", "myaccounts", "My Accounts", "Your accounts at all banks", '{}')

  6. app-money objects in the Navigator:
       ("navigator-dave@quippin",  "navigator", "dave@quippin",  "app-money",
        "navigator", "app", "Money", "Banking and payments",
        '{"DisplayName":"Money","IconPath":"/domatar/icons/money.svg",
          "LaunchPath":"/domatar/money.html"}')
       ("navigator-micha@quippin", "navigator", "micha@quippin", "app-money",
        same attrs)

  7. Navigator links for dave and micha:
       root → app-money          (navigator/app,       seqNum=8)
       app-money → myaccounts    (navigator/container, seqNum=1)

## PART 12 — IMPLEMENTATION TASKS

Pass A — Infrastructure
  A1. docker-compose.yml: add "money" alias to tomcat2 on domatar_net.
  A2. Write mySQL/dump-2024-01-24-money.sql (Part 11).

Pass B — Backend
  B1. Write MoneyInstall.java.
  B2. Write ProfileImpl.java   (GetProfile, SetRole).
  B3. Write BankImpl.java      (GetBank, SetFunds).
  B4. Write AccountsImpl.java  (GetAccounts, CreateAccount, Credit).
  B5. Write AccountImpl.java   (GetAccount, Deposit, Withdraw, Transfer,
                                BankTransfer).
      Pay special attention to the hasRights override that grants both the
      owning customer and the hosting bank access to each account object.
  B6. Write MyAccountsImpl.java (GetMyAccounts, RegisterAccount).
  B7. Write MoneyProfileWui.java, MoneyBankWui.java, MoneyAccountsWui.java,
      MoneyAccountWui.java, MoneyMyAccountsWui.java.
  B8. ImplMap.java: add entries for all five (money, *) classes.
  B9. ActManagerImpl.addAct(): call MoneyInstall.install() after
      SpreadsheetInstall.

Pass C — Frontend
  C1. Write money.html    (role selection and navigation).
  C2. Write bank.html     (bank dashboard: accounts list, deposit/withdraw,
                           create account, set funds).
  C3. Write accounts.html (customer dashboard: account list, pay, bank-transfer).

Pass D — Build and Smoke Test
  D1. Maven package, docker cp WAR to both Tomcats, restart.
  D2. Log in as dave.  Set role = Bank.  Name = "Dave's Bank".  Total = 10000.
  D3. As dave (bank), create an account for micha@quippin with balance 500.
      Verify micha's Navigator shows the account under app-money.
  D4. Log in as micha.  Set role = Customer.
      Open accounts.html, verify "Dave's Bank" account with balance 500.
  D5. As micha, pay dave@quippin 100 at Dave's Bank.
      Verify dave's account at Dave's Bank is created automatically with
      balance 100, and micha's balance drops to 400.
  D6. As dave (bank), set role = Customer.  Set role = Bank at another bank
      (or test with a second user).
      Transfer 200 from micha's account at Dave's Bank to micha's account at
      the second bank.  Verify balances update correctly on both banks.

## PART 13 — OPEN QUESTIONS

  Q1. Trust for Credit across banks: any authenticated Domatar user can call
      Credit, including malicious actors who could credit themselves.  A future
      hardening pass could require the calling bank's DomId to be on a
      pre-approved list, or use a shared secret embedded in the message.

  Q2. Atomicity of BankTransfer: the deduction from the source account and the
      Credit on the destination bank are not atomic.  If the Credit message
      fails, the source is debited but the destination is not credited.  A
      future pass could implement a two-phase commit or a retry queue.

  Q3. Overdraft protection: the spec disallows negative balances but does not
      specify a hold/reserve mechanism.  Concurrent transfers could theoretically
      both read the same balance before either writes.  A future pass could use
      optimistic locking or a compare-and-swap on the balance attribute.

  Q4. Account discovery: a customer who wants to receive payments must have a
      pre-existing account at the paying customer's bank.  A future "request
      account" flow could let a customer knock on a bank's door to ask for
      an account.

  Q5. Transaction history: no tx log is specified in v1.  A future pass would
      add (money, tx) objects recording each Deposit, Withdraw, Transfer, and
      BankTransfer, linked from both the account and a per-user timeline.
