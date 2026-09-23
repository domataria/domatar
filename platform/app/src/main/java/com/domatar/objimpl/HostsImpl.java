/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.objimpl;

import java.util.List;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.Hst;
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

/**
 * Handler for class (domatar, hosts).
 *
 * Lives at:
 *   domatar-&lt;prvActId&gt; / domatar / &lt;prvActId&gt; / hosts
 *
 * Spec-DomatarApp.txt PART 7.1.
 *
 * Operations:
 *   GetObj          - inherited from ObjImpl
 *   Open            - returns one child entry per hst row (any verified user)
 *   ListHsts        - returns full hst table (owner only)
 *   RegisterHst     - upsert hst row, create child obj + link (owner only)
 *   DeregisterHst   - remove hst row, child obj, and link (owner only)
 */
public class HostsImpl extends ObjImpl
{
  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg  = new JsonMsg(msg);
    final String  opr    = inMsg.getOperation();

    if ("Compensate".equals(opr))
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("GetLnks".equals(opr))
      open(opr, inMsg, outMsg, obj);
    else if ("ListHsts".equals(opr))
      listHsts(opr, inMsg, outMsg, obj, msgClient);
    else if ("RegisterHst".equals(opr))
      registerHst(opr, inMsg, outMsg, obj, msgClient);
    else if ("DeregisterHst".equals(opr))
      deregisterHst(opr, inMsg, outMsg, obj, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj,
                           final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(msgClient);
  }

  private boolean isOwner(final DomatarMsgClient msgClient, final Obj obj)
  {
    final String actId = Auth.actId(msgClient);
    return actId != null && obj != null && obj.domId != null
        && actId.equals(obj.domId.actId);
  }

  /**
   * Open: one Lnk entry per row in the local hst table.
   *
   * Reads directly from HstDb (not the lnk table) so the list is always
   * authoritative and current regardless of lnk-table sync state.
   * Returns the standard Navigator Open format (DomId + Lnks) so the
   * Navigator tree renders correctly.
   */
  private void open(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                    final Obj obj)
      throws DomatarException
  {
    final List<Hst> hsts     = HstDb.getAllHsts();
    final String    ssHstId  = obj.domId.hstId;
    final String    prvActId = obj.domId.actId;
    final JsonList  lnkList  = new JsonArrayList();

    for (final Hst h : hsts)
    {
      final DomId   childId = new DomId(ssHstId, "domatar", prvActId, "host-" + h.hstId);
      final String  desc    = h.domain + "  prv:" + h.prvId;
      final JsonMap entry   = new JsonHashMap();
      entry.put("DomId",    childId.toString());
      entry.put("ClsAppId", "domatar");
      entry.put("ClsId",    "host");
      entry.put("ObjName",  h.hstId);
      entry.put("ObjDesc",  desc);
      entry.put("TagAppId", "navigator");
      entry.put("Tag",      "container");
      entry.put("Val",      h.hstId);
      entry.put("SeqNum",   "0");
      lnkList.add(entry);
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("DomId",    obj.domId.toString());
    outAttrs.addAttr("PrvId",    hostingPrvId(obj));
    outAttrs.addAttr("ClsAppId", obj.clsAppId);
    outAttrs.addAttr("ClsId",    obj.clsId);
    outAttrs.addAttr("ObjName",  obj.objName);
    outAttrs.addAttr("ObjDesc",  obj.objDesc);
    outAttrs.addAttr("Attrs",    obj.attrs != null ? obj.attrs.toMap() : new JsonHashMap());
    outAttrs.addAttr("Lnks",     lnkList);
    outMsg.addResponseBody(opr, outAttrs);
  }

  /** ListHsts: full hst table, owner only. */
  private void listHsts(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                        final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!isOwner(msgClient, obj))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    final List<Hst> hsts = HstDb.getAllHsts();
    final JsonList  list = new JsonArrayList();

    for (final Hst h : hsts)
    {
      final JsonMap entry = new JsonHashMap();
      entry.put("HstId",   h.hstId);
      entry.put("Domain",  h.domain);
      entry.put("PrvId",   h.prvId);
      entry.put("Version", String.valueOf(h.version));
      list.add(entry);
    }

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("Hsts", list);
    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * RegisterHst: upsert local hst row, create child (domatar, host) obj,
   * and link hosts → host-&lt;hstId&gt;.
   */
  private void registerHst(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                            final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!isOwner(msgClient, obj))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    final String hstId  = inMsg.getAttr("HstId");
    final String domain = inMsg.getAttr("Domain");
    final String prvId  = inMsg.getAttr("PrvId");

    if (hstId == null || domain == null || prvId == null)
    {
      outMsg.addError(opr, "Missing HstId, Domain, or PrvId");
      return;
    }

    HstDb.updateHst(hstId, domain, prvId);

    final String   ssHstId  = obj.domId.hstId;
    final String   prvActId = obj.domId.actId;
    final DomId    childId  = new DomId(ssHstId, "domatar", prvActId, "host-" + hstId);

    final ObjAttrs childAttrs = new ObjAttrs();
    childAttrs.addAttr("HstId",   hstId);
    childAttrs.addAttr("Domain",  domain);
    childAttrs.addAttr("PrvId",   prvId);
    childAttrs.addAttr("Version", "1");

    ObjDb.addObjIfMissing(childId, "domatar", "host",
        hstId, domain + "  prv:" + prvId, childAttrs);

    if (LnkDb.getLnk(obj.domId, childId, "navigator", "container") == null)
      LnkDb.addLnk(new Lnk(obj.domId, childId,
                            "domatar", "host",
                            hstId, domain + "  prv:" + prvId,
                            "navigator", "container",
                            hstId, 0));

    final ObjAttrs outAttrs = new ObjAttrs();
    outAttrs.addAttr("HstId", hstId);
    outMsg.addResponseBody(opr, outAttrs);
  }

  /**
   * DeregisterHst: remove link, child obj, and hst row.
   * Refuses to remove the provider's own host or "domatar".
   */
  private void deregisterHst(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                              final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (!isOwner(msgClient, obj))
    {
      outMsg.addError(opr, "Not authorized");
      return;
    }

    final String hstId = inMsg.getAttr("HstId");

    if (hstId == null)
    {
      outMsg.addError(opr, "Missing HstId");
      return;
    }

    // Guard: never remove the provider's own host or the root "domatar" host.
    final String prvId = DomatarConfig.getPrvId();

    if (hstId.equals(prvId) || "domatar".equals(hstId))
    {
      outMsg.addError(opr, "Cannot deregister the provider's own host");
      return;
    }

    final String ssHstId  = obj.domId.hstId;
    final String prvActId = obj.domId.actId;
    final DomId  childId  = new DomId(ssHstId, "domatar", prvActId, "host-" + hstId);

    LnkDb.deleteLnks(obj.domId, childId, "navigator", "container", null, null);
    ObjDb.deleteObj(childId);
    HstDb.deleteHst(hstId);

    outMsg.addResponseBody(opr, null);
  }
}
