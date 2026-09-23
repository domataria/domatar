package com.domatar.util;

import com.domatar.core.Context;

/**
 * Head + Body only. JsonMsg has no security surface: provenance travels
 * as the {@code Sec=} POST parameter, not inside Head.
 */
public class JsonMsg
{
  private final JsonMap jsonMap;

  public JsonMsg(String document) throws DomatarException
  {
    jsonMap = Json.parseMap(document);
  }

  public JsonMsg() throws DomatarException
  {
    jsonMap = new JsonHashMap();
  }

  public void addRequestHead(DomId srcId, DomId dstId, Context context) throws DomatarException
  {
    addHead("Request", srcId, dstId, context);
  }

  public void addResponseHead(DomId srcId, DomId dstId, Context context) throws DomatarException
  {
    addHead("Response", srcId, dstId, context);
  }

  private void addHead(String method, DomId srcId, DomId dstId, Context context) throws DomatarException
  {
    JsonMap head = getHead();

    head.put("Method", method); // Request or Response
    head.put("SrcId", srcId.toString());
    head.put("DstId", dstId.toString());
    head.put("TimeStamp", Base64Encoder.encode(System.currentTimeMillis()));

    head.put("Context", contextToJson(context));

    jsonMap.put("Head", head);
  }

  /**
   * Replace this message's informational Context fields. Trust is not
   * written. {@link com.domatar.core.Auth#isVerified} reads the platform
   * client, not this JSON.
   */
  public void setContext(Context context) throws DomatarException
  {
    JsonMap head = getHead();

    head.put("Context", contextToJson(context));
  }

  private JsonMap contextToJson(Context context) throws DomatarException
  {
    JsonMap jContext = new JsonHashMap();

    jContext.put("ActId", context.actId);
    jContext.put("UsrId", context.usrId);
    jContext.put("UsrName", context.usrName);
    jContext.put("UsrIp", context.usrIp);
    jContext.put("Token", context.token);
    jContext.put("HttpHeaders", context.httpHeaders);

    return jContext;
  }

  public void addClsId (String clsAppId, String clsId) throws DomatarException
  {
    JsonMap head = getHead();

    head.put("ClsAppId", clsAppId);
    head.put("ClsId", clsId);
  }

  /** Optional service qualifier for the operation (Spec-Service PART 9). */
  public void addSrvId(String srvAppId, String srvId) throws DomatarException
  {
    JsonMap head = getHead();

    head.put("SrvAppId", srvAppId);
    head.put("SrvId", srvId);
  }

  public String getSrvAppId() throws DomatarException
  {
    return getHead().getString("SrvAppId");
  }

  public String getSrvId() throws DomatarException
  {
    return getHead().getString("SrvId");
  }

  public void addHttpHeaders(JsonMap httpHeaders) throws DomatarException
  {
    JsonMap head = getHead();

    head.put("HttpHeaders", httpHeaders);
  }

  public void addBody(String document) throws DomatarException
  {
    JsonMap body = Json.parseMap(document);

    jsonMap.put("Body", body);
  }

  public void addRequestBody(String operation, ObjAttrs attrs) throws DomatarException
  {
    addBody(true, operation, attrs, null);
  }

  public void addResponseBody(String operation, ObjAttrs attrs) throws DomatarException
  {
    getHead().put("Method", "Response");
    addBody(false, operation, attrs, null);
  }

  public void addError(String operation, String errorMsg) throws DomatarException
  {
    JsonMap head = getHead();

    head.put("Method", "Response");

    addBody(false, operation, null, errorMsg);
  }

  private void addBody(boolean req, String operation, ObjAttrs attrs, String errorMsg) throws DomatarException
  {
    JsonMap body = new JsonHashMap();

    if (!req)
    {
      if (errorMsg == null)
        body.put("Error", "Success");
      else
      {
        body.put("Error", "Failure");
        body.put("ErrorMsg", errorMsg);
      }
    }

    body.put("Operation", operation);

    if (attrs != null)
      body.put("Attrs", attrs.toMap());

    jsonMap.put("Body", body);
  }

  private JsonMap getHead() throws DomatarException
  {
    try
    {
      JsonMap head = jsonMap.getMap("Head");

      if (head != null)
        return head;

      head = new JsonHashMap();

      jsonMap.put("Head", head);

      return head;
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
  }

  public DomId getSrcId() throws DomatarException
  {
    JsonMap head = getHead();

    String domIdStr = (String) head.get("SrcId");

    if (domIdStr == null)
      return null;

    return new DomId(domIdStr);
  }

  public DomId getDstId() throws DomatarException
  {
    JsonMap head = getHead();

    String domIdStr = (String) head.get("DstId");

    if (domIdStr == null)
      return null;

    return new DomId(domIdStr);
  }

  public Context getContext() throws DomatarException
  {
    JsonMap head = getHead();

    JsonMap context = head.getMap("Context");

    if (context == null)
      return null;

    // Informational fields only. A Verified flag in this JSON is not trust.
    return new Context(context.getString("ActId"),
                       context.getString("UsrId"),
                       context.getString("UsrName"),
                       context.getString("UsrIp"),
                       context.getString("Token"),
                       context.getMap("HttpHeaders"));
  }

  public String getClsAppId() throws DomatarException
  {
    JsonMap head = getHead();

    return head.getString("ClsAppId");
  }

  public String getClsId() throws DomatarException
  {
    JsonMap head = getHead();

    return head.getString("ClsId");
  }

  private JsonList addCookies() throws DomatarException
  {
    JsonList cookies = getCookies();

    if (cookies != null)
      return cookies;

    cookies = new JsonArrayList();

    JsonMap body = getBody();

    body.put("Cookies", cookies);

    return cookies;
  }

  public void addCookie(String name, String value, int age) throws DomatarException
  {
    JsonMap cookie = new JsonHashMap();

    cookie.put("Name", name);
    cookie.put("Value", value);
    cookie.put("Age", age);

    JsonList cookies = addCookies();

    cookies.add(cookie);
  }

  public JsonList getCookies() throws DomatarException
  {
    JsonMap body = getBody();

    return body.getList("Cookies");
  }

  public void removeCookies() throws DomatarException
  {
    JsonMap body = getBody();

    body.remove("Cookies");
  }

  public String getWui() throws DomatarException
  {
    return Json.toJson(getBody());
  }

  /**
   * Returns the message Body map (creates an empty one if absent).
   * Used to compute the BodyHash for hop signatures.
   */
  public JsonMap getBodyMap() throws DomatarException
  {
    return getBody();
  }

  private JsonMap getBody() throws DomatarException
  {
    JsonMap body = jsonMap.getMap("Body");

    if (body != null)
      return body;

    body = new JsonHashMap();

    jsonMap.put("Body", body);

    return body;
  }

  public String getMethod() throws DomatarException
  {
    JsonMap head = getHead();

    return head.getString("Method");
  }

  public String getOperation() throws DomatarException
  {
    JsonMap body = getBody();

    return body.getString("Operation");
  }

  public ObjAttrs getAttrs() throws DomatarException
  {
    JsonMap body = getBody();

    JsonMap attrs = body.getMap("Attrs");

    if (attrs == null)
      attrs = new JsonHashMap();

    return new ObjAttrs(attrs);
  }

  public String getAttr(String key) throws DomatarException
  {
    ObjAttrs attrs = getAttrs();

    return attrs.getAttr(key);
  }

  public boolean isSuccess() throws DomatarException
  {
    return "Success".equals(getError());
  }

  public boolean isFailure() throws DomatarException
  {
    return "Failure".equals(getError());
  }

  public boolean isRequest() throws DomatarException
  {
    return "Request".equals(getMethod());
  }

  public boolean isResponse() throws DomatarException
  {
    return "Response".equals(getMethod());
  }

  public String getError() throws DomatarException
  {
    JsonMap body = getBody();

    return body.getString("Error");
  }

  public String getErrorMsg() throws DomatarException
  {
    JsonMap body = getBody();

    return body.getString("ErrorMsg");
  }

  @Override
  public String toString()
  {
    try
    {
      return Json.toJson(jsonMap);
    }
    catch (DomatarException e)
    {
      e.printStackTrace();
      return "Error: Malformed JSON";
    }
  }
}
