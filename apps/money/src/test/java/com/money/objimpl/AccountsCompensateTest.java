package com.money.objimpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.domatar.core.Context;
import com.domatar.core.HttpClient;
import com.domatar.core.Trust;
import com.domatar.crypto.CanonicalJson;
import com.domatar.crypto.Path;
import com.domatar.crypto.Provenance;
import com.domatar.db.DbConnection;
import com.domatar.db.HstDb;
import com.domatar.db.ObjDb;
import com.domatar.install.OpLogInstall;
import com.domatar.log.OpLog;
import com.domatar.saga.SagaSlot;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;

public class AccountsCompensateTest
{
  private static final String[] URLS = {
      System.getenv("DOMATAR_DB_URL"),
      System.getProperty("DOMATAR_DB_URL"),
      "jdbc:mysql://127.0.0.1:9308/domatar?user=root&password=domatar",
      "jdbc:mysql://127.0.0.1:9307/domatar?user=root&password=domatar",
      "jdbc:mysql://127.0.0.1:3306/domatar?user=root&password=domatar"
  };

  private static boolean dbReady;
  private static String jdbcUrl;

  private String hst;
  private String bankAct;
  private String custAct;
  private DomId accountsId;
  private DomId acctId;
  private Obj accounts;
  private Context ctx;
  private Provenance prov;
  private HttpClient client;
  private AccountsImpl impl;

  @BeforeAll
  public static void connectOrSkip() throws Exception
  {
    String ok = null;
    for (final String url : URLS)
    {
      if (url == null || url.isBlank())
        continue;
      try
      {
        DriverManager.getConnection(url + "&serverTimezone=UTC").close();
        ok = url;
        break;
      }
      catch (final SQLException ignored)
      {
      }
    }

    dbReady = ok != null;
    if (dbReady)
    {
      jdbcUrl = ok;
      DbConnection.setConnectStr(ok);
      OpLogInstall.ensureTables();
    }
  }

  @BeforeEach
  public void ids() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip AccountsCompensateTest");

    final String n = Long.toHexString(System.nanoTime());
    hst = "money-t-" + n;
    bankAct = "bank" + n;
    custAct = "cust" + n;
    if (HstDb.getHst(hst) == null)
      HstDb.addHst(hst, "localhost", "testprv");

    accountsId = new DomId(hst, "money", bankAct, "accounts");
    acctId = new DomId(hst, "money", custAct, "acct-" + custAct);
    accounts = new Obj(accountsId, "money", "accounts", "Accounts",
        "Customer accounts", new ObjAttrs());
    ObjDb.addObj(accounts);

    final ObjAttrs acctAttrs = new ObjAttrs();
    acctAttrs.addAttr("CustomerActId", custAct);
    acctAttrs.addAttr("CustomerName", "Cust");
    acctAttrs.addAttr("Balance", "10.00");
    acctAttrs.addAttr("BankActId", bankAct);
    ObjDb.addObj(new Obj(acctId, "money", "account", "Cust", "Money account",
        acctAttrs));

    final DomId ui = new DomId(hst, "ui", bankAct, "uiObj");
    final Path path = Path.root(ui, accountsId, bodyBytes("Debit"), bankAct,
        "testprv");
    prov = Provenance.of(path, null, null);
    ctx = new Context(bankAct, "u", "n", "127.0.0.1", "tok", Trust.ACCOUNT,
        path.contextId(), null);
    client = HttpClient.inbound(accountsId, "localhost", ctx, "/domatar", "",
        prov);
    impl = new AccountsImpl();
  }

  @AfterEach
  public void cleanup() throws Exception
  {
    OpLog.skipVisit(false);
    if (!dbReady || hst == null)
      return;
    exec("DELETE FROM op_msg WHERE HstId=?", hst);
    exec("DELETE FROM op_dst WHERE HstId=?", hst);
    ObjDb.deleteByHstId(hst);
  }

  @Test
  public void debit_attachesSagaEffect() throws Exception
  {
    admit("Debit");
    final JsonMsg ret = handle("Debit", debitAttrs("3.00"));
    assertNull(ret.getErrorMsg());
    assertEquals("7.00", ret.getAttr("Balance"));
    assertEquals("7.00", ObjDb.getObj(acctId).attrs.getAttr("Balance"));

    final JsonMap slot = SagaSlot.parse(client.attachment(SagaSlot.SLOT));
    assertNotNull(slot);
    assertEquals(SagaSlot.STATUS_APPLIED, SagaSlot.status(slot));
    assertEquals("Credit", slot.getString("compensates"));
    final Object effectObj = slot.get("effect");
    assertTrue(effectObj instanceof JsonMap);
    final JsonMap effect = (JsonMap) effectObj;
    assertEquals("3.00", effect.getString("Amount"));
    assertEquals(custAct, effect.getString("CustomerActId"));
    assertEquals(ctx.contextId, effect.getString("OrigContextId"));
  }

  @Test
  public void credit_skipVisit_secondApplyDoesNotDouble() throws Exception
  {
    OpLog.skipVisit(true);
    try
    {
      final ObjAttrs attrs = new ObjAttrs();
      attrs.addAttr("CustomerActId", custAct);
      attrs.addAttr("Amount", "3.00");
      attrs.addAttr("OrigContextId", ctx.contextId);

      final JsonMsg first = handle("Credit", attrs);
      assertNull(first.getErrorMsg());
      assertEquals("13.00", first.getAttr("Balance"));

      final JsonMsg second = handle("Credit", attrs);
      assertNull(second.getErrorMsg());
      assertEquals("13.00", second.getAttr("Balance"));
      assertEquals("13.00", ObjDb.getObj(acctId).attrs.getAttr("Balance"));
      assertEquals(ctx.contextId,
          ObjDb.getObj(acctId).attrs.getAttr(AccountsImpl.SAGA_CREDIT_KEY));
    }
    finally
    {
      OpLog.skipVisit(false);
    }
  }

  private void admit(final String op) throws Exception
  {
    client.snapshotPriors(ctx.contextId, accountsId.toString(), op);
    assertTrue(OpLog.admitIfNeeded(client, request(op, new ObjAttrs()),
        accounts, prov));
  }

  private JsonMsg handle(final String op, final ObjAttrs attrs) throws Exception
  {
    final String ret = impl.handleMsg(request(op, attrs).toString(), accounts,
        "/domatar", "", client);
    return new JsonMsg(ret);
  }

  private JsonMsg request(final String op, final ObjAttrs attrs)
      throws DomatarException
  {
    final DomId src = new DomId(hst, "ui", bankAct, "uiObj");
    final JsonMsg m = new JsonMsg();
    m.addRequestHead(src, accountsId, ctx);
    m.setContext(ctx);
    m.addRequestBody(op, attrs);
    return m;
  }

  private ObjAttrs debitAttrs(final String amount) throws DomatarException
  {
    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("CustomerActId", custAct);
    attrs.addAttr("Amount", amount);
    return attrs;
  }

  private static byte[] bodyBytes(final String op) throws DomatarException
  {
    final JsonMap body = new JsonHashMap();
    body.put("Operation", op);
    return CanonicalJson.canonicalize(body);
  }

  private static void exec(final String sql, final String hstId) throws Exception
  {
    try (Connection c = DriverManager.getConnection(jdbcUrl + "&serverTimezone=UTC");
         PreparedStatement pstmt = c.prepareStatement(sql))
    {
      pstmt.setString(1, hstId);
      pstmt.executeUpdate();
    }
  }
}
