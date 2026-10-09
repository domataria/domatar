/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.objimpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.domatar.core.Context;
import com.domatar.core.HandlerClient;
import com.domatar.core.TestClients;
import com.domatar.crypto.CanonicalJson;
import com.domatar.crypto.Path;
import com.domatar.crypto.Provenance;
import com.domatar.db.DbConnection;
import com.domatar.db.HstDb;
import com.domatar.db.ObjDb;
import com.domatar.install.OpLogInstall;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.travel.remote.FakeTravelHttp;

public class TravelBookTest
{
  private static final String[] URLS = {
      System.getenv("DOMATAR_DB_URL"),
      System.getProperty("DOMATAR_DB_URL"),
      "jdbc:mysql://127.0.0.1:9308/domatar?user=root&password=domatar",
      "jdbc:mysql://127.0.0.1:9307/domatar?user=root&password=domatar",
      "jdbc:mysql://127.0.0.1:3306/domatar?user=root&password=domatar"
  };

  private static boolean dbReady;

  private String hst;
  private String actId;
  private DomId appId;
  private Obj app;
  private Context ctx;
  private HandlerClient client;
  private FakeTravelHttp http;

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
      DbConnection.setConnectStr(ok);
      OpLogInstall.ensureTables();
    }
  }

  @BeforeEach
  public void ids() throws Exception
  {
    assumeTrue(dbReady, "MySQL unreachable; skip TravelBookTest");

    actId = "act" + Long.toHexString(System.nanoTime());
    hst = DomId.subHstId("travel", actId);
    if (HstDb.getHst(hst) == null)
      HstDb.addHst(hst, "localhost", "prv2");

    appId = TravelIds.subHost(actId, TravelIds.APP);
    app = new Obj(appId, "travel", "app", "Travel", "Flights", new ObjAttrs());
    ObjDb.addObj(app);

    final DomId ui = new DomId(hst, "ui", actId, "uiObj");
    final Path path = Path.root(ui, appId, bodyBytes("BookTrip"), actId, "prv2");
    final Provenance prov = Provenance.of(path, null, null);

    ctx = TestClients.account(actId, "u", "n", "127.0.0.1", "tok", path.contextId());
    client = TestClients.inbound(appId, "localhost", ctx, "/domatar", "", prov);
    http = new FakeTravelHttp();
    TravelAppImpl.setClientsForTest(http, "duffel_test_unit");
  }

  @AfterEach
  public void cleanup() throws Exception
  {
    TravelAppImpl.setClientsForTest(null, null);
    if (!dbReady || hst == null)
      return;
    ObjDb.deleteByHstId(hst);
    HstDb.deleteHst(hst);
  }

  @Test
  public void unknownFlight_doesNotCallDuffel() throws Exception
  {
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("Title", "Other trip");
    attrs.addAttr("FlightName", "no-such-flight");
    attrs.addAttr("HotelName", "Hotel");
    attrs.addAttr("Day1", "A");
    attrs.addAttr("Day2", "B");
    attrs.addAttr("Day3", "C");

    final JsonMsg out = new JsonMsg(new TravelAppImpl().handleMsg(
        request(attrs).toString(), app, "/domatar", "", client));

    assertEquals("Failure", out.getError());
    assertEquals(TravelErrors.unknownFlight(), out.getErrorMsg());
    assertEquals(0, http.postCount);
  }

  private JsonMsg request(final ObjAttrs attrs) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();

    msg.addRequestHead(new DomId(hst, "ui", actId, "uiObj"), appId, ctx);
    msg.setContext(ctx);
    msg.addClsId("travel", "app");
    msg.addRequestBody("BookTrip", attrs);
    return msg;
  }

  private static byte[] bodyBytes(final String op) throws DomatarException
  {
    final JsonMap body = new JsonHashMap();

    body.put("Operation", op);
    return CanonicalJson.canonicalize(body);
  }
}
