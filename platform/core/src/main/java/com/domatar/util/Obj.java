package com.domatar.util;

import java.util.List;

import com.domatar.db.LnkDb;

public class Obj
{
  public final DomId domId;
  public final String clsAppId;
  public final String clsId;
  public final String objName;
  public final String objDesc;
  public final ObjAttrs attrs;

  public Obj(DomId domId, DomatarMsgClient msgClient) throws DomatarException
  {
    JsonMsg jsonMsg = new JsonMsg();

    jsonMsg.addResponseBody("GetObj", null);

    JsonMsg objMsg = msgClient.send(domId, jsonMsg);

    ObjAttrs attrs = objMsg.getAttrs();

    this.domId = domId;
    this.clsAppId = attrs.getAttr("ClsAppId");
    this.clsId = attrs.getAttr("ClsId");
    this.objName = attrs.getAttr("ObjName");
    this.objDesc = attrs.getAttr("ObjDesc");
    this.attrs = attrs.getObjAttrs("Attrs");
  }

  public Obj(DomId domId, String clsAppId, String clsId, String objName, String objDesc, ObjAttrs attrs)
  {
    this.domId = domId;
    this.clsAppId = clsAppId;
    this.clsId = clsId;
    this.objName = objName.length() > 40 ? objName.substring(0, 40) : objName;
    this.objDesc = objDesc.length() > 100 ? objDesc.substring(0, 100) : objDesc;
    this.attrs = attrs;
  }

  public Obj modify (DomId domId, String clsAppId, String clsId, String objName, String objDesc, ObjAttrs attrs)
  {
    return new Obj(domId == null ? this.domId : domId,
                   clsAppId == null ? this.clsAppId : clsAppId,
                   clsId == null ? this.clsId : clsId,
                   objName == null ? this.objName : objName,
                   objDesc == null ? this.objDesc : objDesc,
                   attrs == null ? this.attrs : attrs);
  }

  public List<Lnk> getLnks(int maxResults) throws DomatarException
  {
    return getLnks(null, null, null, null, maxResults, false);
  }

  /**
   * Paged children: return up to maxResults links whose SeqNum is at or
   * beyond startSeqNum (ascending order). Pass Long.MIN_VALUE to start
   * from the first child.
   */
  public List<Lnk> getLnks(int maxResults, long startSeqNum) throws DomatarException
  {
    return LnkDb.getLnks(domId, null, null, null, null, maxResults, false, startSeqNum);
  }

  public List<Lnk> getLnks(String tagAppId, String tag, int maxResults) throws DomatarException
  {
    return getLnks(tagAppId, tag, null, null, maxResults, false);
  }

  public List<Lnk> getLnks(String tagAppId, String tag, String valExpression, String seqNumExpression, int maxResults, boolean desc) throws DomatarException
  {
    return LnkDb.getLnks(domId, tagAppId, tag, valExpression, seqNumExpression, maxResults, desc);
  }
}
