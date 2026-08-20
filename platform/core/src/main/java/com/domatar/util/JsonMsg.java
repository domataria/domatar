package com.domatar.util;

import com.domatar.core.Context;
import com.domatar.crypto.Binding;
import com.domatar.crypto.Delegation;
import com.domatar.crypto.Hop;
import com.domatar.crypto.OriginBlock;

import java.util.ArrayList;
import java.util.List;

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
   * Replace this message's Context. Used by trust boundaries (DomatarServlet,
   * Msg.doAction) after they run verifyLogin, to stamp the dispatch context
   * with verified=true (and the locally-resolved actId/usrName) so that
   * downstream local handlers can authorize via a flag read instead of
   * re-running verifyLogin.
   *
   * Note: the Verified field DOES travel on the wire. The receiving
   * Msg.doAction must always overwrite it based on its own local
   * verifyLogin - it never trusts the caller's word for it.
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
    jContext.put("Verified", context.verified ? "true" : "false");
    jContext.put("HttpHeaders", context.httpHeaders);

    JsonList jDomIdPath = domIdsToJsonArray(context.domIdPath);

    jContext.put("DomIdPath", jDomIdPath);

    return jContext;
  }

  // -------------------------------------------------------------------------
  // Sec block (Phase 4+)
  // -------------------------------------------------------------------------

  /**
   * Value object returned by {@link #getSec()}.
   * All fields are null/empty when no Sec block is present.
   */
  public static final class SecEnvelope
  {
    public final OriginBlock origin;
    public final Delegation  delegation;
    public final Binding     binding;
    public final List<Hop>   path;

    public SecEnvelope(final OriginBlock origin,
                       final Delegation  delegation,
                       final Binding     binding,
                       final List<Hop>   path)
    {
      this.origin     = origin;
      this.delegation = delegation;
      this.binding    = binding;
      this.path       = path != null ? path : new ArrayList<>();
    }

    public boolean hasOrigin()     { return origin     != null; }
    public boolean hasDelegation() { return delegation != null; }
    public boolean hasBinding()    { return binding    != null; }
    public boolean hasPath()       { return path != null && !path.isEmpty(); }
  }

  /**
   * Writes the {@code Head.Sec} block containing the origin authentication
   * block and account delegation (Spec-Security.txt PART 11.1).
   * Path is initially empty; use {@link #appendHopToSec} to add hops.
   * Legacy two-arg form (no Binding); prefer the three-arg overload.
   *
   * @param origin     signed OriginBlock (must not be null)
   * @param delegation account delegation or null
   */
  public void addSec(final OriginBlock origin, final Delegation delegation)
      throws DomatarException
  {
    addSec(origin, delegation, null);
  }

  /**
   * Writes {@code Head.Sec} with Origin, optional Delegation, and optional
   * Binding (Spec-OwnIds.txt PART 11).
   */
  public void addSec(final OriginBlock origin,
                     final Delegation  delegation,
                     final Binding     binding) throws DomatarException
  {
    final JsonMap head = getHead();
    final JsonMap sec  = new JsonHashMap();

    sec.put("Origin", origin.toMap());

    if (delegation != null)
      sec.put("Delegation", delegation.toMap());

    if (binding != null)
      sec.put("Binding", binding.toMap());

    head.put("Sec", sec);
  }

  /**
   * Appends a signed {@link Hop} to the {@code Head.Sec.Path} list
   * (Spec-Security.txt PART 8.2 / 11.2).  Creates the Path array if it
   * does not exist yet.  No-op if the Sec block is absent.
   *
   * @param hop the hop to append (typically produced by {@link com.domatar.crypto.PathChain#append})
   */
  public void appendHopToSec(final Hop hop) throws DomatarException
  {
    final JsonMap head = getHead();
    final JsonMap sec  = head.getMap("Sec");

    if (sec == null)
      return; // no Sec block — unsigned message; skip silently

    JsonList path = sec.getList("Path");

    if (path == null)
    {
      path = new JsonArrayList();
      sec.put("Path", path);
    }

    path.add(hop.toMap());
  }

  /**
   * Returns the parsed {@link SecEnvelope} from {@code Head.Sec}, or an
   * envelope with all fields null/empty if the Sec block is absent.
   */
  public SecEnvelope getSec() throws DomatarException
  {
    final JsonMap head = getHead();
    final JsonMap sec  = head.getMap("Sec");

    if (sec == null)
      return new SecEnvelope(null, null, null, null);

    final JsonMap originMap  = sec.getMap("Origin");
    final JsonMap delegMap   = sec.getMap("Delegation");
    final JsonMap bindingMap = sec.getMap("Binding");
    final JsonList pathList  = sec.getList("Path");

    final OriginBlock origin     = OriginBlock.fromMap(originMap);
    final Delegation  delegation = Delegation.fromMap(delegMap);
    final Binding     binding    = Binding.fromMap(bindingMap);

    final List<Hop> path = new ArrayList<>();

    if (pathList != null)
    {
      for (int i = 0; i < pathList.size(); i++)
      {
        final Object elem = pathList.get(i);
        if (elem instanceof JsonMap)
          path.add(Hop.fromMap((JsonMap) elem));
      }
    }

    return new SecEnvelope(origin, delegation, binding, path);
  }

  /**
   * Returns true iff this message carries a {@code Head.Sec} block with an
   * OriginBlock inside. Used by {@code HttpClient.send} to decide whether to
   * add a Sec block.
   */
  public boolean hasSec() throws DomatarException
  {
    final JsonMap head = getHead();
    final JsonMap sec  = head.getMap("Sec");
    return sec != null && sec.getMap("Origin") != null;
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

    String verifiedStr = context.getString("Verified");
    boolean verified = "true".equals(verifiedStr);

    return new Context(context.getString("ActId"),
                       context.getString("UsrId"),
                       context.getString("UsrName"),
                       context.getString("UsrIp"),
                       context.getString("Token"),
                       verified,
                       context.getMap("HttpHeaders"),
                       jsonArrayToDomIds(context.getList("DomIdPath")));
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
   * Used by {@code HttpClient} to compute the BodyHash for an OriginBlock.
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

  private static JsonList domIdsToJsonArray(DomId[] domIds) throws DomatarException
  {
    int len = domIds.length;

    JsonList jarray = new JsonArrayList();

    for (int i = 0; i < len; i++)
      jarray.add(domIds[i].toString());

    return jarray;
  }

  private static DomId[] jsonArrayToDomIds(JsonList jsonArray) throws DomatarException
  {
    int len = jsonArray.size();

    DomId[] domIds = new DomId[len];

    for (int i = 0; i < len; i++)
      domIds[i] = new DomId(jsonArray.getString(i));

    return domIds;
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
