package com.domatar.util;

import java.util.List;

import com.domatar.core.DomatarConfig;
import com.domatar.core.HttpClient;
import com.domatar.db.HstDb;
import com.domatar.db.ObjDb;
import com.domatar.db.OpLogDb;
import com.domatar.log.OpDst;
import com.domatar.saga.Compensate;
import com.domatar.saga.CompensateResult;
import com.domatar.saga.SagaSlot;

public class ObjImpl implements DomatarInterface
{
  @Override
  public String handleMsg(final String msg, Obj obj, final String contextPath, final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg = new JsonMsg(msg);

    final String opr = inMsg.getOperation();

    final JsonMsg outMsg = new JsonMsg();

    if ("Compensate".equals(opr))
    {
      obj = resolveObj(inMsg, obj);
      return compensate(inMsg, obj, msgClient);
    }

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    obj = resolveObj(inMsg, obj);

    if (obj == null || obj.domId == null)
    {
      outMsg.addError(opr, "Obj not found");

      return outMsg.toString();
    }

    if ("GetObj".equals(opr))
      getObj(inMsg, outMsg, obj, msgClient);
    else if ("GetLnks".equals(opr))
      openObj(inMsg, outMsg, obj, msgClient);
    else
      outMsg.addError(opr, "Unknown operation");

    return outMsg.toString();
  }

  /**
   * Path-provenance policy hook (Q2, Spec-Security PART 8.9 / KD14).
   *
   * Override to return {@code true} when a {@code Trust.NONE} or
   * {@code Trust.PATH} verdict should be fatal for this handler (reject
   * rather than run). Verification itself is unconditional from Phase 4;
   * this hook is policy only. When this returns {@code true},
   * {@code Msg.doAction} refuses dispatch unless the inbound verdict is
   * {@code Trust.ACCOUNT}.
   *
   * Default: {@code false}.
   */
  public boolean requiresPath()
  {
    return false;
  }

  /**
   * Authorization hook. Each subclass's handleMsg calls this at its top
   * and replies "Not authorized" (via notAuthorized) when it returns false.
   *
   * The base implementation returns true (public). Subclasses override
   * to declare a stricter policy. For now there are two patterns:
   *   - public:   return true;
   *   - verified: return Auth.isVerified(inMsg);
   *
   * Auth.isVerified is a Trust read via Context.isVerified() - the trust
   * boundary (DomatarServlet for browser entry, Msg.doAction for
   * cross-prv inbound) has already run verifyLogin once for this
   * request. Per-handler hasRights() therefore costs no DB hit; it is
   * pure authorization policy on top of an already-established
   * identity.
   *
   * Mixed-policy subclasses can switch on inMsg.getOperation() to pick
   * a different rule per operation. Richer policies (owner-match,
   * follower-status, ban-checking, ...) belong here too: they are the
   * point where dynamic, object-level authorization decisions live.
   */
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (inMsg != null && "Compensate".equals(inMsg.getOperation()))
      return admitCompensate(inMsg, msgClient);
    return true;
  }

  public String compensate(final JsonMsg inMsg, final Obj obj,
      final DomatarMsgClient msgClient) throws DomatarException
  {
    if (!(msgClient instanceof HttpClient))
    {
      final JsonMsg out = new JsonMsg();
      out.addResponseBody("Compensate", new ObjAttrs(
          CompensateResult.failed(
              inMsg != null ? inMsg.getAttr("OrigContextId") : null,
              inMsg != null && inMsg.getDstId() != null
                  ? inMsg.getDstId().toString() : null,
              inMsg != null ? inMsg.getAttr("OrigMsgName") : null,
              "client").toMap()));
      return out.toString();
    }
    return Compensate.run(this, inMsg, obj, (HttpClient) msgClient);
  }

  private static boolean admitCompensate(final JsonMsg inMsg,
      final DomatarMsgClient msgClient) throws DomatarException
  {
    if (!(msgClient instanceof HttpClient))
      return false;

    final HttpClient client = (HttpClient) msgClient;
    final String origMsgName = inMsg.getAttr("OrigMsgName");
    final String origContextId = inMsg.getAttr("OrigContextId");
    final DomId dst = inMsg.getDstId();
    final OpDst visit = dst == null ? null
        : OpLogDb.getVisit(dst.hstId, origContextId, dst.toString(), origMsgName);
    final JsonMap slot = SagaSlot.parse(
        client.attachment(origContextId, origMsgName, SagaSlot.SLOT));
    final String actId = client.inboundContext() != null
        ? client.inboundContext().actId : null;
    return Compensate.admit(origMsgName, visit, slot, actId,
        Compensate.inboundSrcDomId(client, client.inboundProvenance()));
  }

  /**
   * Standard "Not authorized" reply. Returned by handleMsg when
   * hasRights returns false.
   */
  public String notAuthorized(final JsonMsg inMsg) throws DomatarException
  {
    final JsonMsg outMsg = new JsonMsg();

    outMsg.addError(inMsg.getOperation(), "Not authorized");

    return outMsg.toString();
  }

  private void getObj(final JsonMsg inMsg, final JsonMsg outMsg, final Obj obj, final DomatarMsgClient msgClient) throws DomatarException
  {
    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("ClsAppId", obj.clsAppId);
    outAttrs.addAttr("ClsId", obj.clsId);
    outAttrs.addAttr("ObjName", obj.objName);
    outAttrs.addAttr("ObjDesc", obj.objDesc);
    outAttrs.addAttr("Attrs", obj.attrs.toMap());

    outMsg.addResponseBody("GetObj", outAttrs);
  }

  /**
   * Open protocol (Spec-Navigator PART 4; Spec-Icons.txt PART 7.3).
   *
   * Returns the obj's metadata and all of its outgoing lnks.
   * The lnk list is what the Navigator uses to populate the tree:
   * each Lnk entry becomes one child node.
   *
   * MaxLnks from the request body caps the page size; defaults to 500.
   * StartSeqNum (optional) is the paging cursor: only links whose SeqNum
   * is >= StartSeqNum are returned, in SeqNum order. When a further page
   * exists the response carries HasMore=true and NextSeqNum, the cursor to
   * pass as StartSeqNum on the next call.
   *
   * Response shape:
   *   { "Operation": "GetLnks",
   *     "Attrs": {
   *       "DomId":    "<dotted DomId>",
   *       "PrvId":    "<hosting provider>",
   *       "ClsAppId": "...",  "ClsId": "...",
   *       "ObjName":  "...",  "ObjDesc": "...",
   *       "Attrs":    { ... obj attrs ... },
   *       "Lnks":     [ { "DomId","ClsAppId","ClsId","ObjName","ObjDesc",
   *                        "TagAppId","Tag","Val","SeqNum" }, ... ],
   *       "HasMore":  "true"|"false",
   *       "NextSeqNum": "<cursor, present only when HasMore>",
   *       "AssetOrigin": "{scheme}://{host[:port]}"  (BrowserOrigin, else PublicDomain / Domain),
   *       "AssetContextPath": "" | "/domatar"   (front door vs wire paths)
   *     }
   *   }
   */
  /**
   * When the request carries a ClsId envelope, sendLocal passes obj=null;
   * load the destination row from the message head.
   */
  private static Obj resolveObj(final JsonMsg inMsg, final Obj obj) throws DomatarException
  {
    if (obj != null && obj.domId != null)
      return obj;

    final String clsAppId = inMsg.getClsAppId();
    final String clsId    = inMsg.getClsId();

    if (clsAppId != null && !clsAppId.isEmpty()
        && clsId != null && !clsId.isEmpty())
      return ObjDb.getObj(inMsg.getDstId());

    return obj;
  }

  private void openObj(final JsonMsg inMsg, final JsonMsg outMsg, final Obj obj, final DomatarMsgClient msgClient) throws DomatarException
  {
    int maxLnks = 500;

    final String maxLnksStr = inMsg.getAttr("MaxLnks");

    if (maxLnksStr != null)
    {
      try
      {
        maxLnks = Integer.parseInt(maxLnksStr);
      }
      catch (NumberFormatException ignored)
      {
      }
    }

    if (maxLnks < 0)
      maxLnks = 0;

    long startSeqNum = Long.MIN_VALUE;

    final String startSeqNumStr = inMsg.getAttr("StartSeqNum");

    if (startSeqNumStr != null)
    {
      try
      {
        startSeqNum = Long.parseLong(startSeqNumStr);
      }
      catch (NumberFormatException ignored)
      {
      }
    }

    // Fetch one extra link so we can tell the caller whether a further page
    // exists and hand back the cursor (NextSeqNum) that begins it.
    final int fetch = (maxLnks < Integer.MAX_VALUE) ? maxLnks + 1 : maxLnks;

    List<Lnk> lnks = obj.getLnks(fetch, startSeqNum);

    final boolean hasMore = lnks.size() > maxLnks;
    long nextSeqNum = 0;

    if (hasMore)
    {
      nextSeqNum = lnks.get(maxLnks).seqNum;
      lnks       = lnks.subList(0, maxLnks);
    }

    final JsonList lnkList = new JsonArrayList();

    for (final Lnk lnk : lnks)
    {
      final JsonHashMap lnkMap = new JsonHashMap();

      lnkMap.put("DomId",    lnk.lnkDomId.toString());
      lnkMap.put("ClsAppId", lnk.lnkClsAppId);
      lnkMap.put("ClsId",    lnk.lnkClsId);
      lnkMap.put("ObjName",  lnk.lnkObjName);
      lnkMap.put("ObjDesc",  lnk.lnkObjDesc);
      lnkMap.put("TagAppId", lnk.tagAppId);
      lnkMap.put("Tag",      lnk.tag);
      lnkMap.put("Val",      lnk.val);
      lnkMap.put("SeqNum",   String.valueOf(lnk.seqNum));

      lnkList.add(lnkMap);
    }

    final ObjAttrs outAttrs = new ObjAttrs();

    outAttrs.addAttr("DomId",    obj.domId.toString());
    outAttrs.addAttr("PrvId",    hostingPrvId(obj));
    outAttrs.addAttr("ClsAppId", obj.clsAppId);
    outAttrs.addAttr("ClsId",    obj.clsId);
    outAttrs.addAttr("ObjName",  obj.objName);
    outAttrs.addAttr("ObjDesc",  obj.objDesc);
    outAttrs.addAttr("Attrs",    obj.attrs.toMap());
    outAttrs.addAttr("Lnks",     lnkList);
    outAttrs.addAttr("HasMore",  String.valueOf(hasMore));

    if (hasMore)
      outAttrs.addAttr("NextSeqNum", String.valueOf(nextSeqNum));

    final String origin = DomatarConfig.getAssetOrigin();

    if (origin != null)
      outAttrs.addAttr("AssetOrigin", origin);

    outAttrs.addAttr("AssetContextPath", DomatarConfig.getAssetContextPath());

    outMsg.addResponseBody("GetLnks", outAttrs);
  }

  /**
   * Provider that physically hosts this object: {@code hst.PrvId}, else
   * the local provider (Open runs on the hosting node).
   */
  protected static String hostingPrvId(final Obj obj) throws DomatarException
  {
    if (obj != null && obj.domId != null)
    {
      final String hstId = obj.domId.hstId;

      if (hstId != null && !hstId.isEmpty())
      {
        final Hst hst = HstDb.getHst(hstId);

        if (hst != null && hst.prvId != null && !hst.prvId.isEmpty())
          return hst.prvId;
      }
    }

    final String local = DomatarConfig.getPrvId();

    return local != null ? local : "";
  }
}
