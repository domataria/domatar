/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.util;

public class Act
{
  public final String actId;
  public final String usrId;
  public final String usrName;
  public final String token;

  /** Fingerprint algorithm version that minted this actId (default 1). */
  public final int fpVersion;

  public Act (String actId,
              String usrId,
              String usrName,
              String token)
  {
    this(actId, usrId, usrName, token, 1);
  }

  public Act (String actId,
              String usrId,
              String usrName,
              String token,
              int fpVersion)
  {
    this.actId     = actId;
    this.usrId     = usrId;
    this.usrName   = usrName;
    this.token     = token;
    this.fpVersion = fpVersion;
  }

  public String getUsrHandle()
  {
    return DomId.getUsrHandle(usrId);
  }

  public static Act getLocalPrvAct(DomId curObjId, DomatarMsgClient msgClient) throws DomatarException
  {
    JsonMsg msg = new JsonMsg();

    DomId actCacheId = new DomId(DomId.getUsrHst(curObjId.hstId), "act", curObjId.actId, "actCache");

    msg.addClsId("act", "actCache");

    msg.addResponseBody("GetAct", null);

    JsonMsg retMsg = msgClient.send(actCacheId, msg);

    Act act = new Act(actCacheId.actId,
                      retMsg.getAttr("UsrId"),
                      retMsg.getAttr("UsrName"),
                      null);

    return act;
  }
}
