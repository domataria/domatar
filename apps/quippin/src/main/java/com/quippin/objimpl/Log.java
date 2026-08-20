package com.quippin.objimpl;

import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

public class Log
{
  public static JsonMsg add(final String logType, final JsonMsg inMsg, final DomatarMsgClient msgClient) throws DomatarException
  {
    return add(logType, inMsg, null, msgClient);
  }

  public static JsonMsg add(final String logType, final JsonMsg inMsg, final JsonMap attrMap, final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId dstId = inMsg.getDstId();
    final String operation = inMsg.getOperation();
    final String actId = dstId.actId;
    return add(logType, actId, operation, attrMap, msgClient);
  }

  public static JsonMsg add(final String logType, final String actId, final String operation, final JsonMap attrMap, final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId domId = new DomId("quippin~" + actId, "quippin", actId, "logs");
    final JsonMsg logMsg = new JsonMsg();
    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("LogType", logType);
    attrs.addAttr("Operation", operation);
    if (attrMap != null)
      attrs.addAll(attrMap);
    logMsg.addRequestBody("Log", attrs);
    logMsg.addClsId("quippin", "logs");
    final JsonMsg rspMsg = msgClient.send(domId, logMsg);
    return rspMsg;
  }
}
