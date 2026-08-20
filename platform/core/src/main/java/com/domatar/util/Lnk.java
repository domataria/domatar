package com.domatar.util;

public class Lnk
{
  public final DomId domId;
  public final DomId lnkDomId;
  public final String lnkClsAppId;
  public final String lnkClsId;
  public String lnkObjName;
  public String lnkObjDesc;
  public final String tagAppId;
  public final String tag;
  public String val;
  public long seqNum;

  public Lnk(final DomId domId,
              final DomId lnkDomId,
              final String lnkClsAppId,
              final String lnkClsId,
              final String lnkObjName,
              final String lnkObjDesc,
              final String tagAppId,
              final String tag,
              final String val,
              final long seqNum)
  {
    this.domId = domId;
    this.lnkDomId = lnkDomId;
    this.lnkClsAppId = lnkClsAppId;
    this.lnkClsId = lnkClsId;
    this.lnkObjName = lnkObjName;
    this.lnkObjDesc = lnkObjDesc;
    this.tagAppId = tagAppId;
    this.tag = tag;
    this.val = val;
    this.seqNum = seqNum;
  }
}
