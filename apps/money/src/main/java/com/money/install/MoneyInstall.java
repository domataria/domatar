/*
 * Copyright (c) 2024 Domatar
 */

package com.money.install;

import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.AppInstall;
import com.domatar.install.CatalogInstall;
import com.domatar.install.ClsInstall;
import com.domatar.install.SrvInstall;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Install routine for the Money app (Spec-Money.txt PART 5).
 *
 * Creates the per-user money~<actId> sub-host, profile object,
 * myaccounts container, the app-money node in the Navigator,
 * and the Navigator skeleton links.
 *
 * Every step is idempotent: a second call is a no-op.
 */
public class MoneyInstall implements AppInstall
{
  @Override
  public void installProvider(final String prvId, final String domain)
      throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, "money");
    refreshAccountsDescriptors();
  }

  @Override
  public void installUser(final String actId,
                          final String usrId,
                          final String usrName,
                          final String prvId,
                          final String domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    install(actId, usrId, usrName, domain, prvId, msgClient);
  }

  private static void install(final String actId,
                               final String usrId,
                               final String usrName,
                               final String domain,
                               final String prvId,
                               final DomatarMsgClient msgClient) throws DomatarException
  {
    final String moneyHstId = DomId.subHstId("money", actId);
    final String navHstId   = DomId.subHstId("navigator", actId, prvId);

    final DomId rootId       = new DomId(navHstId,   "navigator", actId, "root");
    final DomId appMoneyId   = new DomId(moneyHstId, "money",     actId, "app-money");
    final DomId profileId    = new DomId(moneyHstId, "money", actId, "profile");
    final DomId myAccountsId = new DomId(moneyHstId, "money", actId, "myaccounts");

    // 1. Register sub-host.
    if (HstDb.getHst(moneyHstId) == null)
      HstDb.addHst(moneyHstId, domain, prvId);

    // 2. Profile object — role starts empty; user chooses in money.html.
    final ObjAttrs profileAttrs = new ObjAttrs();
    profileAttrs.addAttr("Role", "");
    ObjDb.addObjIfMissing(profileId, "money", "profile",
                          "Profile", "Money role", profileAttrs);

    // 3. MyAccounts container — present for all users regardless of role.
    ObjDb.addObjIfMissing(myAccountsId, "money", "myaccounts",
                          "My Accounts", "Your accounts at all banks");

    // 4. app-money node on money~<actId>.
    ObjDb.addObjIfMissing(appMoneyId, "money", "app",
                          "Money", "Banking and payments");
    ObjDb.reclassObj(appMoneyId, "money", "app");

    // 5. root -> app-money link  (seqNum=9: after Spreadsheet at 7, skipping 8 used by other apps)
    addLnkIfMissing(rootId, appMoneyId,
                    "money", "app",
                    "Money", "Banking and payments",
                    "navigator", "app", null, 9);

    // 6. app-money -> profile link  (seqNum=1)
    addLnkIfMissing(appMoneyId, profileId,
                    "money", "profile",
                    "Profile", "Money role",
                    "money", "profile", null, 1);

    // 6b. app-money -> myaccounts link  (seqNum=3)
    addLnkIfMissing(appMoneyId, myAccountsId,
                    "money", "myaccounts",
                    "My Accounts", "Your accounts at all banks",
                    "money", "myaccounts", null, 3);

    // 7. Services container + class container + service/slim-class descriptor objects.
    //    seqNum=9 keeps srvs after the data containers (profile=1, myaccounts=3, bank=4)
    SrvInstall.ensureSrvsContainer(appMoneyId,
        "Service descriptors for Money", "money", 9);
    ClsInstall.ensureClssContainer(appMoneyId,
        "Class descriptors for Money", "money", 10);

    // money.app — structured LLM-native entry point with natural-language descriptions
    SrvInstall.upsertSrvObj(appMoneyId, "money", "app",
        "Money app entry point (LLM-native operations)",
        "{" +
        "\"Description\":\"Money app entry point. Query banking profile, list accounts with balances, inspect a specific account, view bank details, and transfer funds.\"," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetProfile\",\"Description\":\"Get the current user's financial role (customer or bank) and whether they have a linked bank.\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"GetMyAccounts\",\"Description\":\"Get all bank accounts owned by the current user, including balance and bank name.\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"GetAccount\",\"Description\":\"Get details for a specific account by its DomId, including customer name, balance, and bank name.\",\"SideEffect\":\"Read\"," +
        "\"Parms\":[{\"Name\":\"AccountDomId\",\"Type\":\"String\",\"Description\":\"The DomId of the account to retrieve.\"}]}," +
        "{\"Name\":\"GetBank\",\"Description\":\"Get the current user's bank profile, including total and available funds. Only valid if the user's role is bank.\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"GetAccounts\",\"Description\":\"Get all customer accounts held at the current user's bank. Only valid if the user's role is bank.\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"Transfer\",\"Description\":\"Transfer money from the current user's account to another user.\",\"SideEffect\":\"Write\"," +
        "\"Parms\":[{\"Name\":\"Amount\",\"Type\":\"String\",\"Description\":\"The amount to transfer.\"},{\"Name\":\"ToActId\",\"Type\":\"String\",\"Description\":\"The account ID of the recipient.\"}]}" +
        "]," +
        "\"Attrs\":\"DisplayName, IconPath, LaunchPath\"" +
        "}");

    ClsInstall.upsertClsImplementing(appMoneyId, "money", "app",
        "Money app entry point (LLM-native operations)",
        "{\"ClsAppId\":\"money\",\"ClsId\":\"app\"," +
        "\"Implements\":[\"money.app\"]," +
        "\"Auth\":\"isVerified\"" +
        "}");

    // money.profile — user role profile
    SrvInstall.addSrvObj(profileId, "money", "profile",
        "User money role profile (customer or bank)",
        "{\"SrvAppId\":\"money\",\"SrvId\":\"profile\"," +
        "\"Attrs\":[{\"Name\":\"Role\",\"Type\":\"String\"}]," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetProfile\",\"Type\":{\"Role\":\"String\"},\"Parms\":[]}," +
        "{\"Name\":\"SetRole\",\"Parms\":[{\"Name\":\"Role\",\"Type\":\"String\"},{\"Name\":\"BankName\",\"Type\":\"String?\"}],\"Type\":{\"Role\":\"String\"}}]}");

    ClsInstall.upsertClsImplementing(profileId, "money", "profile",
        "User money role profile (customer or bank)",
        "{\"ClsAppId\":\"money\",\"ClsId\":\"profile\"," +
        "\"Implements\":[\"money.profile\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"money.profile\",\"Name\":\"GetProfile\",\"SideEffect\":\"Read\"}" +
        "]}");

    // money.bank — bank object
    SrvInstall.addSrvObj(profileId, "money", "bank",
        "A bank object managing a fund pool and customer accounts",
        "{\"SrvAppId\":\"money\",\"SrvId\":\"bank\"," +
        "\"Attrs\":[{\"Name\":\"Name\",\"Type\":\"String\"},{\"Name\":\"TotalFunds\",\"Type\":\"String\"},{\"Name\":\"AvailableFunds\",\"Type\":\"String\"}]," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetBank\",\"Type\":{\"Name\":\"String\",\"TotalFunds\":\"String\",\"AvailableFunds\":\"String\"},\"Parms\":[]}," +
        "{\"Name\":\"SetFunds\",\"Parms\":[{\"Name\":\"TotalFunds\",\"Type\":\"String\"},{\"Name\":\"AvailableFunds\",\"Type\":\"String\"}],\"Type\":{\"TotalFunds\":\"String\",\"AvailableFunds\":\"String\"}}]}");

    ClsInstall.upsertClsImplementing(profileId, "money", "bank",
        "A bank object managing a fund pool and customer accounts",
        "{\"ClsAppId\":\"money\",\"ClsId\":\"bank\"," +
        "\"Implements\":[\"money.bank\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"money.bank\",\"Name\":\"GetBank\",\"SideEffect\":\"Read\"}" +
        "]}");

    upsertAccountsDescriptors(profileId);

    // money.account — individual bank account
    SrvInstall.addSrvObj(profileId, "money", "account",
        "A customer bank account with balance and transfer operations",
        "{\"SrvAppId\":\"money\",\"SrvId\":\"account\"," +
        "\"Attrs\":[{\"Name\":\"CustomerActId\",\"Type\":\"String\"},{\"Name\":\"CustomerName\",\"Type\":\"String\"},{\"Name\":\"Balance\",\"Type\":\"String\"},{\"Name\":\"BankActId\",\"Type\":\"String\"},{\"Name\":\"BankName\",\"Type\":\"String\"}]," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetAccount\",\"Type\":{\"CustomerActId\":\"String\",\"CustomerName\":\"String\",\"Balance\":\"String\",\"BankActId\":\"String\",\"BankName\":\"String\"},\"Parms\":[]}," +
        "{\"Name\":\"Deposit\",\"Parms\":[{\"Name\":\"Amount\",\"Type\":\"String\"}],\"Type\":{\"Balance\":\"String\"}}," +
        "{\"Name\":\"Withdraw\",\"Parms\":[{\"Name\":\"Amount\",\"Type\":\"String\"}],\"Type\":{\"Balance\":\"String\"}}," +
        "{\"Name\":\"Transfer\",\"Parms\":[{\"Name\":\"ToAccountDomId\",\"Type\":\"String\"},{\"Name\":\"Amount\",\"Type\":\"String\"}],\"Type\":{\"FromBalance\":\"String\",\"ToBalance\":\"String\"}}," +
        "{\"Name\":\"BankTransfer\",\"Parms\":[{\"Name\":\"ToCustomerActId\",\"Type\":\"String\"},{\"Name\":\"Amount\",\"Type\":\"String\"}],\"Type\":{\"Balance\":\"String\"}}]}");

    ClsInstall.upsertClsImplementing(profileId, "money", "account",
        "A customer bank account with balance and transfer operations",
        "{\"ClsAppId\":\"money\",\"ClsId\":\"account\"," +
        "\"Implements\":[\"money.account\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"money.account\",\"Name\":\"GetAccount\",\"SideEffect\":\"Read\"}" +
        "]}");

    // money.myaccounts — customer-side accounts index
    SrvInstall.addSrvObj(profileId, "money", "myaccounts",
        "Customer-side index of all accounts across all banks",
        "{\"SrvAppId\":\"money\",\"SrvId\":\"myaccounts\",\"Attrs\":[]," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetMyAccounts\",\"Type\":{\"Accounts\":[{\"AccountDomId\":\"String\",\"Balance\":\"String\",\"BankActId\":\"String\",\"BankName\":\"String\"}]},\"Parms\":[]}," +
        "{\"Name\":\"RegisterAccount\",\"Parms\":[{\"Name\":\"AccountDomId\",\"Type\":\"String\"},{\"Name\":\"BankActId\",\"Type\":\"String\"},{\"Name\":\"BankName\",\"Type\":\"String?\"}],\"Type\":{}}]}");

    ClsInstall.upsertClsImplementing(profileId, "money", "myaccounts",
        "Customer-side index of all accounts across all banks",
        "{\"ClsAppId\":\"money\",\"ClsId\":\"myaccounts\"," +
        "\"Implements\":[\"money.myaccounts\"]," +
        "\"MsgPolicy\":[" +
        "{\"Srv\":\"money.myaccounts\",\"Name\":\"GetMyAccounts\",\"SideEffect\":\"Read\"}" +
        "]}");
  }

  private static final String ACCOUNTS_SRV_JSON =
      "{\"SrvAppId\":\"money\",\"SrvId\":\"accounts\",\"Attrs\":[]," +
      "\"Msgs\":[" +
      "{\"Name\":\"GetAccounts\",\"Type\":{\"Accounts\":[{\"CustomerActId\":\"String\",\"CustomerName\":\"String\",\"Balance\":\"String\",\"BankActId\":\"String\",\"BankName\":\"String\"}]},\"Parms\":[]}," +
      "{\"Name\":\"CreateAccount\",\"Parms\":[{\"Name\":\"CustomerActId\",\"Type\":\"String\"},{\"Name\":\"CustomerName\",\"Type\":\"String?\"},{\"Name\":\"BankActId\",\"Type\":\"String\"},{\"Name\":\"BankName\",\"Type\":\"String?\"}],\"Type\":{\"Balance\":\"String\"}}," +
      "{\"Name\":\"Debit\",\"Description\":\"Decrease a customer account at this bank.\"," +
      "\"SideEffect\":\"Write\",\"Compensates\":\"Credit\"," +
      "\"Parms\":[{\"Name\":\"CustomerActId\",\"Type\":\"String\"},{\"Name\":\"Amount\",\"Type\":\"String\"}]}," +
      "{\"Name\":\"Credit\",\"Description\":\"Increase a customer account at this bank.\"," +
      "\"SideEffect\":\"Write\"," +
      "\"Parms\":[{\"Name\":\"CustomerActId\",\"Type\":\"String\"},{\"Name\":\"Amount\",\"Type\":\"String\"}],\"Type\":{\"Balance\":\"String\"}}]}";

  private static final String ACCOUNTS_CLS_JSON =
      "{\"ClsAppId\":\"money\",\"ClsId\":\"accounts\"," +
      "\"Implements\":[\"money.accounts\"]," +
      "\"MsgPolicy\":[" +
      "{\"Srv\":\"money.accounts\",\"Name\":\"GetAccounts\",\"SideEffect\":\"Read\"}," +
      "{\"Srv\":\"money.accounts\",\"Name\":\"Debit\",\"SideEffect\":\"Write\"," +
      "\"Compensates\":\"Credit\"}," +
      "{\"Srv\":\"money.accounts\",\"Name\":\"Credit\",\"SideEffect\":\"Write\"}" +
      "]}";

  static void upsertAccountsDescriptors(final DomId baseId)
      throws DomatarException
  {
    SrvInstall.upsertSrvObj(baseId, "money", "accounts",
        "Bank-side container of all customer accounts at one bank",
        ACCOUNTS_SRV_JSON);
    ClsInstall.upsertClsImplementing(baseId, "money", "accounts",
        "Bank-side container of all customer accounts at one bank",
        ACCOUNTS_CLS_JSON);
  }

  /**
   * WHY: Setup only calls installProvider for existing users. Re-upsert
   * money.accounts so Debit Compensates Credit lands on live descriptors.
   */
  private static void refreshAccountsDescriptors() throws DomatarException
  {
    for (final Obj cls : ObjDb.listClsDescriptors())
    {
      if (cls.domId == null || !"money".equals(cls.domId.appId)
          || !"accountsCls".equals(cls.domId.objId))
        continue;
      upsertAccountsDescriptors(cls.domId);
    }
  }

  public static void addLnkIfMissing(final DomId domId,
                                      final DomId lnkDomId,
                                      final String lnkClsAppId,
                                      final String lnkClsId,
                                      final String lnkObjName,
                                      final String lnkObjDesc,
                                      final String tagAppId,
                                      final String tag,
                                      final String val,
                                      final long seqNum) throws DomatarException
  {
    if (LnkDb.getLnk(domId, lnkDomId, tagAppId, tag) == null)
      LnkDb.addLnk(new Lnk(domId, lnkDomId,
                            lnkClsAppId, lnkClsId,
                            lnkObjName, lnkObjDesc,
                            tagAppId, tag,
                            val, seqNum));
  }
}
