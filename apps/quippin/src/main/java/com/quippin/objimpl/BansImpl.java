package com.quippin.objimpl;

import java.util.List;

import com.domatar.core.Auth;
import com.domatar.core.LoginRemote;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Act;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.ObjImpl;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

public class BansImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg,
                          final Obj obj,
                          final String contextPath,
                          final String contextRealPath,
                          final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg = new JsonMsg(msg);
    final String opr = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("Ban".equals(opr))
      ban(opr, inMsg, outMsg, msgClient);
    else if ("Unban".equals(opr))
      unban(opr, inMsg, outMsg, msgClient);
    else if ("GetActBans".equals(opr))
      getActBans(opr, inMsg, outMsg, msgClient);
    else if ("GetIpBans".equals(opr))
      getIpBans(opr, inMsg, outMsg, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  // Verified: a user's bans container is logged-in only.
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }

  private void ban(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                   final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId dstDomId = inMsg.getDstId();
    final String banActId = inMsg.getAttr("ActId");
    final String banIp = inMsg.getAttr("Ip");

    if (banActId != null)
    {
      final String objId = IdGen.createId("banAct", banActId, '_');
      final DomId banId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, objId);
      final Obj banObj = ObjDb.getObj(banId);

      if (banObj == null)
      {
        final ObjAttrs attrs = new ObjAttrs();
        final JsonList list = new JsonArrayList();

        if (banIp != null)
        {
          list.add(banIp);
          attrs.addAttr("Ips", list);
        }

        final Obj newBanObj = new Obj(banId, "quippin", "banAct", "Ban " + banActId, "", attrs);
        ObjDb.addObj(newBanObj);

        final DomId bansDomId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, "bans");
        LnkDb.addLnk(new Lnk(bansDomId, banId,
                              "quippin", "banAct",
                              "Ban " + banActId, "",
                              "quippin", "ban",
                              banActId, System.currentTimeMillis()));
      }
      else
      {
        final JsonList list = banObj.attrs.getAttrList("Ips");

        if (!list.contains(banIp))
        {
          list.add(banIp);
          ObjDb.modifyObj(banObj);
        }
      }
    }

    if (banIp != null)
    {
      final String objId = IdGen.createId("banIp", banIp, '_');
      final DomId banId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, objId);
      final Obj banObj = ObjDb.getObj(banId);

      if (banObj == null)
      {
        final ObjAttrs attrs = new ObjAttrs();
        attrs.addAttr("Ip", banIp);

        final JsonList list = new JsonArrayList();

        if (banActId != null)
        {
          list.add(banActId);
          attrs.addAttr("Acts", list);
        }

        final Obj newBanObj = new Obj(banId, "quippin", "banAct", "Ban " + banIp, "", attrs);
        ObjDb.addObj(newBanObj);

        final DomId bansDomId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, "bans");
        LnkDb.addLnk(new Lnk(bansDomId, banId,
                              "quippin", "banIp",
                              "Ban " + banIp, "",
                              "quippin", "ban",
                              banIp, System.currentTimeMillis()));
      }
      else
      {
        final JsonList list = banObj.attrs.getAttrList("Acts");

        if (!list.contains(banActId))
        {
          list.add(banActId);
          ObjDb.modifyObj(banObj);
        }
      }
    }

    outMsg.addResponseBody(opr, null);
  }

  private void unban(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                     final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId dstDomId  = inMsg.getDstId();
    final DomId bansDomId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, "bans");
    final String banActId = inMsg.getAttr("ActId");
    final String banIp    = inMsg.getAttr("Ip");

    if (banActId != null)
    {
      final String objId = IdGen.createId("banAct", banActId, '_');
      final DomId banId  = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, objId);
      ObjDb.deleteObj(banId);
      LnkDb.deleteLnks(bansDomId, banId, "quippin", "ban", null, null);
    }

    if (banIp != null)
    {
      final String objId = IdGen.createId("banIp", banIp, '_');
      final DomId banId  = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, objId);
      ObjDb.deleteObj(banId);
      LnkDb.deleteLnks(bansDomId, banId, "quippin", "ban", null, null);
    }

    outMsg.addResponseBody(opr, null);
  }

  private void getActBans(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId dstDomId = inMsg.getDstId();
    final List<Obj> objList = ObjDb.getObjPrefix(dstDomId, "banAct", null, 1000);
    final JsonList banList = new JsonArrayList(objList.size());

    for (final Obj obj : objList)
    {
      try
      {
        final String bannedActId = IdGen.getIdSuffix(obj.domId.objId);
        final JsonList ipList = obj.attrs.getAttrList("Ips");
        final JsonMap attrMap = new JsonHashMap();
        final Act bannedAct = LoginRemote.getAct(bannedActId, null, msgClient);
        attrMap.put("UsrName", bannedAct.usrName);
        attrMap.put("UsrId", bannedAct.usrId);
        attrMap.put("ActId", bannedAct.actId);
        if (ipList != null)
          attrMap.put("Ips", ipList);
        banList.add(attrMap);
      }
      catch (Exception e)
      {
        System.out.println(e.toString());
      }
    }

    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("BannedActs", banList);
    outMsg.addResponseBody(opr, attrs);
  }

  private void getIpBans(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                         final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId dstDomId = inMsg.getDstId();
    final List<Obj> objList = ObjDb.getObjPrefix(dstDomId, "banIp", null, 1000);
    final JsonList banList = new JsonArrayList(objList.size());

    for (final Obj obj : objList)
    {
      try
      {
        String bannedIp = obj.attrs.getAttr("Ip");

        if (bannedIp == null)
          bannedIp = IdGen.getIdSuffix(obj.domId.objId);

        final JsonList actList = obj.attrs.getAttrList("Acts");
        final JsonList usrList = new JsonArrayList();

        for (final Object actId : actList)
        {
          final Act bannedAct = LoginRemote.getAct((String) actId, null, msgClient);
          final JsonMap usrMap = new JsonHashMap();
          usrMap.put("UsrName", bannedAct.usrName);
          usrMap.put("UsrId", bannedAct.usrId);
          usrList.add(usrMap);
        }

        final JsonMap attrMap = new JsonHashMap();
        attrMap.put("Ip", bannedIp);
        attrMap.put("Usrs", usrList);
        banList.add(attrMap);
      }
      catch (Exception e)
      {
        System.out.println(e.toString());
      }
    }

    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("BannedIps", banList);
    outMsg.addResponseBody(opr, attrs);
  }
}
