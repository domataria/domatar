package com.quippin.objimpl;

import java.util.List;

import com.domatar.core.Auth;
import com.domatar.core.Context;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.IdGen;
import com.domatar.util.JsonArrayList;
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

public class LogsImpl extends ObjImpl
{
  private static long lastDelete = 0;

  @Override
  public String handleMsg(final String msg, final Obj obj, final String contextPath,
                          final String contextRealPath, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg inMsg = new JsonMsg(msg);
    final String opr = inMsg.getOperation();
    final JsonMsg outMsg = new JsonMsg();

    if (!hasRights(inMsg, obj, msgClient))
      return notAuthorized(inMsg);

    if ("Log".equals(opr))
      log(opr, inMsg, outMsg, msgClient);
    else if ("GetLogs".equals(opr))
      getLogs(opr, inMsg, outMsg, msgClient);
    else
      return super.handleMsg(msg, obj, contextPath, contextRealPath, msgClient);

    return outMsg.toString();
  }

  // Verified: log writes/reads are logged-in-only.
  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return Auth.isVerified(inMsg);
  }

  private void log(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                   final DomatarMsgClient msgClient) throws DomatarException
  {
    deleteLogs();

    final DomId dstDomId = inMsg.getDstId();
    final DomId srcDomId = inMsg.getSrcId();
    final String curTimeStr = IdGen.getCurTimeBase64();
    final String idSuffix = curTimeStr + "-" + IdGen.getRandBase64();
    final String objId = IdGen.createId("log", idSuffix);
    final DomId logId = new DomId(dstDomId.hstId, "quippin", dstDomId.actId, objId);
    final ObjAttrs attrs = new ObjAttrs(inMsg.getAttrs());
    final Context context = inMsg.getContext();
    final String usrName = context.usrName;
    final String usrId = context.usrId;
    final String usrIp = context.usrIp;
    final DomId[] path = context.domIdPath;
    final StringBuilder pathBuilder = new StringBuilder();

    for (final DomId domId : path)
    {
      if (pathBuilder.length() > 0)
        pathBuilder.append("\n\n");
      pathBuilder.append(domId.toString());
    }

    final String pathStr = pathBuilder.toString();
    final String time = IdGen.getTimeFromIdBase10(curTimeStr);
    final String logType = attrs.getAttr("LogType");
    final String operation = attrs.getAttr("Operation");
    final JsonMap httpHeaders = context.httpHeaders;

    attrs.addAttr("LogType", logType);
    attrs.addAttr("Operation", operation);
    attrs.addAttr("Time", time);
    attrs.addAttr("UsrName", usrName);
    attrs.addAttr("UsrId", usrId);
    attrs.addAttr("UsrIp", usrIp);
    attrs.addAttr("DomIdPath", pathStr);
    attrs.addAttr("HttpHeaders", httpHeaders);

    final String objName = "Log " + logType;
    final String objDesc = objName + " : " + operation;
    final Obj logObj = new Obj(logId, "quippin", "log", objName, objDesc, attrs);
    ObjDb.addObj(logObj);

    // Navigator lnk: logs container -> this log row.
    LnkDb.addLnk(new Lnk(dstDomId, logId,
                          "quippin", "log",
                          objName, objDesc,
                          "quippin", "log",
                          null, System.currentTimeMillis()));

    outMsg.addResponseBody(opr, null);
  }

  private void getLogs(final String opr, final JsonMsg inMsg, final JsonMsg outMsg,
                       final DomatarMsgClient msgClient) throws DomatarException
  {
    final DomId dstDomId = inMsg.getDstId();
    final List<Obj> objList = ObjDb.getObjPrefix(dstDomId, "log", null, 1000);
    final JsonList logList = new JsonArrayList(objList.size());

    for (final Obj obj : objList)
    {
      try
      {
        final JsonMap attrMap = obj.attrs.toMap();
        logList.add(attrMap);
      }
      catch (Exception e)
      {
        System.out.println(e.toString());
      }
    }

    final ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("Logs", logList);
    outMsg.addResponseBody(opr, attrs);
  }

  private void deleteLogs() throws DomatarException
  {
    // Delete logs more than an hour old
    if (updateLastDelete())
    {
      final long curTime = System.currentTimeMillis();
      final long hourAgo = curTime - 60 * 60 * 1000;
      final String from = "";
      final String to = IdGen.getBase64(hourAgo);
      ObjDb.deleteObjRange("quippin", "log", from, to);
      LnkDb.deleteLnkRange("quippin", "log", from, to);
    }
  }

  private static synchronized boolean updateLastDelete()
  {
    final long curTime = System.currentTimeMillis();
    final long minuteAgo = curTime - 60 * 1000;

    // Delete once a minute
    if (lastDelete < minuteAgo)
    {
      lastDelete = curTime;
      return true;
    }

    return false;
  }
}
