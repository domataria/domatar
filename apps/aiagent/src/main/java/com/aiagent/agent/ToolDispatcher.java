/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.domatar.core.Context;
import com.domatar.core.DomatarConfig;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

import com.aiagent.agent.AgentDefaults;
import com.aiagent.agent.DynamicToolRegistry;
import com.aiagent.agent.MsgsSchemaBuilder;
import com.aiagent.llm.LlmTool;
import com.aiagent.llm.LlmToolCall;

/**
 * Dispatches a single LlmToolCall as a live Domatar message.
 * Spec-AIAgent.txt PART 15.4 / PART 16.2 / PART 16.3 / PART 16.6.
 *
 * Dispatch paths, checked in order:
 *
 *   Base-tool path (PART 16.2) — call.name is listApps or useApp.
 *     These two tools are always available; listApps returns the user's
 *     installed apps and useApp registers app-specific dynamic tools.
 *
 *   Dynamic-tool path (PART 16.3) — call.name matches an entry in the
 *     per-turn DynamicToolRegistry (populated by useApp).
 *     dispatchDynamic() forwards the call to the registered app DomId.
 *
 *   Fixed-verb path (PART 16.1) — call.name is one of the five legacy
 *     AgentDefaults.TOOL_* constants; executeFixed() handles them.
 *
 *   Legacy/dotted path — call.name is "<ClsAppId>.<ClsId>.<MsgName>".
 */
public class ToolDispatcher
{
  /**
   * Result of one tool-call dispatch.
   *
   * status      "ok" | "error"
   * resultJson  The response body JSON (success) or error message text.
   * targetSov   The destination object's DomId; null when args are malformed.
   * argsJson    The LLM's original arguments JSON (for audit trail).
   */
  public static class Result
  {
    public final String status;
    public final String resultJson;
    public final DomId  targetSov;
    public final String argsJson;

    Result(final String status, final String resultJson,
           final DomId targetSov, final String argsJson)
    {
      this.status     = status;
      this.resultJson = resultJson;
      this.targetSov  = targetSov;
      this.argsJson   = argsJson;
    }
  }

  // -----------------------------------------------------------------------
  // Public API
  // -----------------------------------------------------------------------

  /**
   * Execute one tool call.
   *
   * @param callerDomId  the agent loop's caller DomId (src for the inner msg)
   * @param callerCtx    the caller's verified Context (forwarded as-is)
   * @param call         the LLM's tool call request
   * @param msgClient    message router
   * @param registry     per-turn dynamic tool registry; may be null
   */
  public static Result execute(final DomId               callerDomId,
                                final Context             callerCtx,
                                final LlmToolCall         call,
                                final DomatarMsgClient    msgClient,
                                final DynamicToolRegistry registry)
  {
    final String argsJson = normalizeArgs(call.argumentsJson);
    DomId        dst      = null;

    try
    {
      // a. Base-tool path: listApps and useApp are handled before arg parsing.
      if (AgentDefaults.TOOL_LIST_APPS.equals(call.name))
        return executeListApps(callerDomId, callerCtx, msgClient);

      if (AgentDefaults.TOOL_USE_APP.equals(call.name))
        return executeUseApp(callerDomId, callerCtx, argsJson, msgClient, registry);

      // b. Dynamic-tool path: look up in registry before fixed-verb check.
      if (registry != null)
      {
        final DynamicToolRegistry.Entry e = registry.lookup(call.name);
        if (e != null)
          return dispatchDynamic(callerDomId, callerCtx, call, msgClient, e);
      }

      // c. Parse args for the remaining paths.
      final JsonMap argsMap;
      try
      {
        argsMap = Json.parseMap(argsJson);
      }
      catch (Exception e)
      {
        return new Result("error",
            "invalid JSON in tool arguments: " + e.getMessage(),
            null, argsJson);
      }

      // d. Fixed-verb path vs. legacy dotted-name path.
      if (isFixedVerb(call.name))
        return executeFixed(callerDomId, callerCtx, call, argsMap, msgClient);

      // e. Extract _target for legacy dotted-name path.
      final JsonMap targetMap = extractTargetMap(argsMap);
      if (targetMap == null)
      {
        return new Result("error",
            "_target is missing or not an object in: " + argsJson,
            null, argsJson);
      }

      // f. Decompose tool name: "<ClsAppId>.<ClsId>.<MsgName>"
      final String[] parts = call.name.split("\\.", 3);
      if (parts.length != 3)
      {
        return new Result("error",
            "tool name does not have three dot-separated parts: " + call.name,
            null, argsJson);
      }

      final String clsAppId = parts[0];
      final String clsId    = parts[1];
      final String msgName  = parts[2];

      final String hstId = targetMap.getString("HstId");
      final String appId = targetMap.getString("AppId");
      final String actId = targetMap.getString("ActId");
      String       objId = targetMap.getString("ObjId");

      if (hstId == null || appId == null || actId == null)
      {
        return new Result("error",
            "_target is missing HstId/AppId/ActId in: " + argsJson,
            null, argsJson);
      }

      objId = resolveObjId(clsAppId, clsId, msgName, objId, actId);
      if (objId == null || objId.isEmpty())
      {
        return new Result("error",
            "_target ObjId is required in: " + argsJson,
            null, argsJson);
      }

      dst = new DomId(hstId, appId, actId, objId);

      // e. Build the request body (all args except _target)
      final ObjAttrs body = new ObjAttrs();

      if (argsMap != null)
      {
        for (final Map.Entry<String, Object> e : argsMap.entrySet())
        {
          if ("_target".equals(e.getKey()))
            continue;

          final Object v = e.getValue();
          body.addAttr(e.getKey(), v != null ? v.toString() : "");
        }
      }

      return dispatch(callerDomId, callerCtx, dst, clsAppId, clsId,
                      msgName, body, argsJson, msgClient);
    }
    catch (DomatarException e)
    {
      return new Result("error", e.getMessage(), dst, argsJson);
    }
  }

  /** Backward-compatible overload — passes a null registry. */
  public static Result execute(final DomId            callerDomId,
                                final Context          callerCtx,
                                final LlmToolCall      call,
                                final DomatarMsgClient msgClient)
  {
    return execute(callerDomId, callerCtx, call, msgClient, null);
  }

  /**
   * Returns the canonical "<ClsAppId>.<ClsId>.<Operation>" name for any
   * tool call. Used by the consent layer and audit (PART 17.3 / T2.2).
   *
   * Fixed read verbs -> stable synthetic names.
   * SendMsg          -> ClsAppId.ClsId.Operation from args.
   * Legacy/featured  -> call.name unchanged.
   */
  public static String canonicalOpName(final LlmToolCall call)
  {
    final String name = call.name;

    if (AgentDefaults.TOOL_GET_OBJ.equals(name))  return "domatar.obj.GetObj";
    if (AgentDefaults.TOOL_GET_LNKS.equals(name)) return "domatar.obj.GetLnks";
    if (AgentDefaults.TOOL_GET_CLS.equals(name))  return "domatar.cls.GetCls";
    if (AgentDefaults.TOOL_GET_HSTS.equals(name)) return "domatar.hosts.ListHsts";

    if (AgentDefaults.TOOL_SEND_MSG.equals(name))
    {
      try
      {
        final JsonMap argsMap  = Json.parseMap(
            call.argumentsJson != null ? call.argumentsJson : "{}");
        final String  clsAppId = argsMap.getString("ClsAppId");
        final String  clsId    = argsMap.getString("ClsId");
        final String  op       = argsMap.getString("Operation");

        if (clsAppId != null && clsId != null && op != null)
          return clsAppId + "." + clsId + "." + op;
      }
      catch (Exception ignored)
      {
      }

      return name;
    }

    return name;
  }

  // -----------------------------------------------------------------------
  // Base-tool handlers: listApps, useApp (PART 16.2)
  // -----------------------------------------------------------------------

  /**
   * Handle the listApps tool call.
   * Calls GetLnks on the caller's Navigator root and returns a JSON array
   * of {ObjName, ObjDesc} for every linked object (= every installed app).
   */
  private static Result executeListApps(final DomId            callerDomId,
                                         final Context          callerCtx,
                                         final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String actId      = callerDomId.actId;
    final Result lnksResult = getNavRootLnks(callerDomId, callerCtx, actId, msgClient);
    if (!"ok".equals(lnksResult.status))
      return new Result("error",
          "listApps: could not read Navigator root — " + lnksResult.resultJson,
          null, "{}");

    final JsonList lnks = parseLnks(lnksResult.resultJson);

    final JsonList out = new JsonArrayList();
    for (final Object o : lnks)
    {
      if (!(o instanceof JsonMap))
        continue;
      final JsonMap lnk   = (JsonMap) o;
      final JsonMap entry = new JsonHashMap(2);
      entry.put("ObjName", lnk.getString("ObjName"));
      entry.put("ObjDesc", lnk.getString("ObjDesc"));
      out.add(entry);
    }
    return new Result("ok", Json.toJson(out), null, "{}");
  }

  /**
   * Handle the useApp tool call.
   * 1. Parses the "name" argument.
   * 2. Finds the matching app via GetLnks on the Navigator root
   *    (case-insensitive ObjName match).
   * 3. Calls GetCls on the app entry-point.
   * 4. Builds LlmTools via MsgsSchemaBuilder.
   * 5. Registers them in the registry (replaces any prior tools).
   * 6. Returns a summary JSON: {"loaded": [...], "descriptions": {...}}.
   */
  private static Result executeUseApp(final DomId               callerDomId,
                                       final Context             callerCtx,
                                       final String              argsJson,
                                       final DomatarMsgClient    msgClient,
                                       final DynamicToolRegistry registry)
      throws DomatarException
  {
    // Parse the "name" argument.
    final String requestedName;
    try
    {
      final JsonMap args = Json.parseMap(argsJson != null ? argsJson : "{}");
      requestedName = args.getString("name");
    }
    catch (Exception e)
    {
      return new Result("error",
          "useApp: invalid JSON arguments — " + e.getMessage(), null, argsJson);
    }

    if (requestedName == null || requestedName.isEmpty())
      return new Result("error",
          "useApp: missing required argument 'name'", null, argsJson);

    final String actId      = callerDomId.actId;
    final Result lnksResult = getNavRootLnks(callerDomId, callerCtx, actId, msgClient);
    if (!"ok".equals(lnksResult.status))
      return new Result("error",
          "useApp: could not read Navigator root — " + lnksResult.resultJson,
          null, argsJson);

    final JsonList lnks = parseLnks(lnksResult.resultJson);

    // Find the matching app link.
    // Pass 1: case-insensitive exact match on ObjName.
    // Pass 2: fuzzy — every word in requestedName appears somewhere in ObjName or ObjDesc.
    JsonMap            matchedLnk     = null;
    final List<String> validNamesDesc = new ArrayList<>();
    final List<JsonMap> allLnks       = new ArrayList<>();

    for (final Object o : lnks)
    {
      if (!(o instanceof JsonMap))
        continue;
      final JsonMap lnk  = (JsonMap) o;
      final String  name = lnk.getString("ObjName");
      final String  desc = lnk.getString("ObjDesc");
      if (name == null)
        continue;
      allLnks.add(lnk);
      validNamesDesc.add(desc != null ? name + " — " + desc : name);
      if (requestedName.equalsIgnoreCase(name))
        matchedLnk = lnk;
    }

    if (matchedLnk == null)
    {
      final String   reqLower = requestedName.toLowerCase();
      final String[] words    = reqLower.split("[\\s_-]+");
      for (final JsonMap lnk : allLnks)
      {
        final String haystack = (lnk.getString("ObjName") + " " +
                                 (lnk.getString("ObjDesc") != null ? lnk.getString("ObjDesc") : ""))
                                .toLowerCase();
        boolean allMatch = true;
        for (final String w : words)
          if (!haystack.contains(w))
          {
            allMatch = false;
            break;
          }
        if (allMatch)
        {
          matchedLnk = lnk;
          break;
        }
      }
    }

    if (matchedLnk == null)
      return new Result("error",
          "useApp: app '" + requestedName + "' not found. "
          + "Available apps (name — description): " + validNamesDesc, null, argsJson);

    // Parse the app entry-point DomId from the Lnk.
    final String domIdStr = matchedLnk.getString("DomId");

    final DomId appDomId;
    try
    {
      appDomId = new DomId(domIdStr);
    }
    catch (Exception e)
    {
      return new Result("error",
          "useApp: malformed DomId in Navigator lnk: " + domIdStr, null, argsJson);
    }

    // All Navigator links have ClsAppId="domatar" (the generic app class).
    // Use the app's own appId from its DomId for the class descriptor lookup.
    final String clsAppId = appDomId.appId;  // e.g. "spreadsheet"
    final String clsId    = "app";

    // Fetch the class descriptor via GetCls.
    final String getClsArgs =
        "{\"_target\":{" +
          "\"HstId\":\"" + esc(appDomId.hstId) + "\"," +
          "\"AppId\":\"domatar\"," +
          "\"ActId\":\"" + esc(appDomId.actId) + "\"," +
          "\"ObjId\":\"cls\"" +
        "}," +
        "\"ClsAppId\":\"" + esc(clsAppId) + "\"," +
        "\"ClsId\":\""    + esc(clsId)    + "\"}";

    final LlmToolCall getClsCall = new LlmToolCall("_useapp_getcls_",
        AgentDefaults.TOOL_GET_CLS, getClsArgs);
    final Result clsResult = execute(callerDomId, callerCtx, getClsCall, msgClient, null);

    if (!"ok".equals(clsResult.status) || clsResult.resultJson == null)
      return new Result("error",
          "useApp: GetCls failed for '" + requestedName + "' — "
          + clsResult.resultJson, null, argsJson);

    // Unwrap the Attrs envelope from the GetCls response.
    final JsonMap descriptor;
    try
    {
      final JsonMap raw    = Json.parseMap(clsResult.resultJson);
      final Object  nested = raw.get("Attrs");
      descriptor = (nested instanceof JsonMap) ? (JsonMap) nested : raw;
    }
    catch (Exception e)
    {
      return new Result("error",
          "useApp: could not parse GetCls response — " + e.getMessage(),
          null, argsJson);
    }

    // Build tool entries and register them (uniquifying names on collision).
    final List<MsgsSchemaBuilder.ToolEntry> toolEntries = MsgsSchemaBuilder.build(descriptor);
    if (toolEntries.isEmpty())
      return new Result("error",
          "useApp: no operations found in descriptor for '" + requestedName + "'",
          null, argsJson);

    if (registry != null)
      registry.register(toolEntries, appDomId);

    // Build the summary response shown to the LLM, using the unique LLM names
    // assigned by the registry (which may differ from the bare operation names
    // when two services contribute the same operation name).
    final List<LlmTool> registered = registry != null
                               ? registry.getTools()
                               : java.util.Collections.emptyList();
    final JsonList loaded = new JsonArrayList();
    final JsonMap  descs  = new JsonHashMap(registered.size());
    for (final LlmTool t : registered)
    {
      loaded.add(t.name);
      descs.put(t.name, t.description);
    }
    final JsonMap summary = new JsonHashMap(2);
    summary.put("loaded",       loaded);
    summary.put("descriptions", descs);
    return new Result("ok", Json.toJson(summary), appDomId, argsJson);
  }

  /**
   * Dispatch a dynamic tool call to its registered app DomId.
   *
   * The LLM's argumentsJson is used as the message body. The REAL bare
   * operation (Entry.operation) is used, not the unique LLM-facing tool name.
   * When the Entry carries service identity, addSrvId is called on the
   * outgoing message so the receiving handler can disambiguate overloaded
   * operations (Spec-Service PART 9).
   */
  private static Result dispatchDynamic(final DomId                     callerDomId,
                                         final Context                   callerCtx,
                                         final LlmToolCall               call,
                                         final DomatarMsgClient          msgClient,
                                         final DynamicToolRegistry.Entry e)
      throws DomatarException
  {
    final String argsJson = normalizeArgs(call.argumentsJson);
    final DomId  dst      = e.targetDomId;

    final ObjAttrs body = new ObjAttrs();
    try
    {
      final JsonMap argsMap = Json.parseMap(argsJson);
      if (argsMap != null)
      {
        for (final Map.Entry<String, Object> kv : argsMap.entrySet())
        {
          final Object v = kv.getValue();
          body.addAttr(kv.getKey(), v != null ? v.toString() : "");
        }
      }
    }
    catch (Exception ex)
    {
      return new Result("error",
          "invalid JSON in tool arguments: " + ex.getMessage(), null, argsJson);
    }

    final JsonMsg req = new JsonMsg();
    req.addRequestHead(callerDomId, dst, callerCtx);
    req.addClsId(dst.appId, "app");
    req.addRequestBody(e.operation, body);
    if (e.srvAppId != null && e.srvId != null)
      req.addSrvId(e.srvAppId, e.srvId);

    final JsonMsg resp = msgClient.send(dst, req);
    if (resp.isSuccess())
      return new Result("ok", Json.toJson(resp.getAttrs().toMap()), dst, argsJson);
    else
      return new Result("error",
          resp.getErrorMsg() != null ? resp.getErrorMsg() : "call failed",
          dst, argsJson);
  }

  // -----------------------------------------------------------------------
  // Navigator root helper
  // -----------------------------------------------------------------------

  /**
   * Call GetLnks on the Navigator root for the given actId.
   * Uses the provider-qualified local navigator host
   * ({@code navigator~&lt;actId&gt;~&lt;prvId&gt;}), with the same
   * {@link DomId#localSubHstId} fallback as Navigator and App Store.
   */
  private static Result getNavRootLnks(final DomId            callerDomId,
                                        final Context          callerCtx,
                                        final String           actId,
                                        final DomatarMsgClient msgClient)
  {
    final String navHstId = DomId.localSubHstId(
        "navigator", actId, DomatarConfig.getPrvId());
    final String navArgs =
        "{\"_target\":{" +
          "\"HstId\":\"" + esc(navHstId) + "\"," +
          "\"AppId\":\"navigator\"," +
          "\"ActId\":\"" + esc(actId) + "\"," +
          "\"ObjId\":\"root\"" +
        "}}";
    final LlmToolCall getLnksCall = new LlmToolCall("_base_getlnks_",
        AgentDefaults.TOOL_GET_LNKS, navArgs);
    return execute(callerDomId, callerCtx, getLnksCall, msgClient, null);
  }

  /**
   * Parse the "Lnks" array out of a GetLnks resultJson.
   * Returns an empty list on any parse failure.
   */
  private static JsonList parseLnks(final String resultJson)
  {
    try
    {
      final JsonMap  map  = Json.parseMap(resultJson);
      final Object   lnks = map.get("Lnks");
      if (lnks instanceof JsonList)
        return (JsonList) lnks;
    }
    catch (Exception ignored)
    {
    }
    return new JsonArrayList();
  }

  /**
   * Normalises a raw argumentsJson string from the LLM.
   * Handles null, the JSON literal "null", and empty strings — all map to "{}".
   */
  private static String normalizeArgs(final String raw)
  {
    if (raw == null || raw.isEmpty() || "null".equals(raw.trim()))
      return "{}";
    return raw;
  }

  private static String esc(final String s)
  {
    if (s == null)
      return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  // -----------------------------------------------------------------------
  // Fixed-verb dispatch (T2.1)
  // -----------------------------------------------------------------------

  private static boolean isFixedVerb(final String name)
  {
    return AgentDefaults.TOOL_GET_OBJ.equals(name)
        || AgentDefaults.TOOL_GET_LNKS.equals(name)
        || AgentDefaults.TOOL_GET_CLS.equals(name)
        || AgentDefaults.TOOL_GET_HSTS.equals(name)
        || AgentDefaults.TOOL_SEND_MSG.equals(name);
  }

  private static Result executeFixed(final DomId            callerDomId,
                                      final Context          callerCtx,
                                      final LlmToolCall      call,
                                      final JsonMap          argsMap,
                                      final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final String argsJson = call.argumentsJson != null ? call.argumentsJson : "{}";
    final String name     = call.name;

    final JsonMap targetMap = extractTargetMap(argsMap);
    if (targetMap == null)
    {
      return new Result("error",
          "_target is missing or not an object in: " + argsJson,
          null, argsJson);
    }

    final String hstId = targetMap.getString("HstId");
    final String appId = targetMap.getString("AppId");
    final String actId = targetMap.getString("ActId");
    final String objId = targetMap.getString("ObjId");

    String   clsAppId;
    String   clsId;
    String   operation;
    final ObjAttrs body = new ObjAttrs();
    DomId    dst;

    if (AgentDefaults.TOOL_GET_OBJ.equals(name))
    {
      if (hstId == null || appId == null || actId == null)
        return missingTarget(argsJson);

      if (objId == null || objId.isEmpty())
        return missingObjId(argsJson);

      dst       = new DomId(hstId, appId, actId, objId);
      clsAppId  = argsMap.getString("ClsAppId");
      clsId     = argsMap.getString("ClsId");

      operation = "GetObj";
    }
    else if (AgentDefaults.TOOL_GET_LNKS.equals(name))
    {
      if (hstId == null || appId == null || actId == null)
        return missingTarget(argsJson);

      if (objId == null || objId.isEmpty())
        return missingObjId(argsJson);

      dst       = new DomId(hstId, appId, actId, objId);
      clsAppId  = argsMap.getString("ClsAppId");
      clsId     = argsMap.getString("ClsId");

      operation = "GetLnks";

      final String maxLnks     = argsMap.getString("MaxLnks");
      final String startSeqNum = argsMap.getString("StartSeqNum");

      if (maxLnks != null)
        body.addAttr("MaxLnks",     maxLnks);
      if (startSeqNum != null)
        body.addAttr("StartSeqNum", startSeqNum);
    }
    else if (AgentDefaults.TOOL_GET_CLS.equals(name))
    {
      if (hstId == null || actId == null)
        return missingTarget(argsJson);

      dst       = new DomId(hstId, "domatar", actId, "cls");
      clsAppId  = "domatar";
      clsId     = "cls";
      operation = "GetCls";

      final String clsAppIdArg = argsMap.getString("ClsAppId");
      final String clsIdArg    = argsMap.getString("ClsId");

      if (clsAppIdArg != null)
        body.addAttr("ClsAppId", clsAppIdArg);
      if (clsIdArg    != null)
        body.addAttr("ClsId",    clsIdArg);
    }
    else if (AgentDefaults.TOOL_GET_HSTS.equals(name))
    {
      if (hstId == null || actId == null)
        return missingTarget(argsJson);

      dst       = new DomId(hstId, "domatar", actId, "hosts");
      clsAppId  = "domatar";
      clsId     = "hosts";
      operation = "ListHsts";
    }
    else // TOOL_SEND_MSG
    {
      if (hstId == null || appId == null || actId == null)
        return missingTarget(argsJson);

      if (objId == null || objId.isEmpty())
        return missingObjId(argsJson);

      dst      = new DomId(hstId, appId, actId, objId);
      clsAppId = argsMap.getString("ClsAppId");
      clsId    = argsMap.getString("ClsId");
      operation = argsMap.getString("Operation");

      if (clsAppId == null || clsAppId.isEmpty()
          || clsId == null || clsId.isEmpty()
          || operation == null || operation.isEmpty())
      {
        return new Result("error",
            "ClsAppId, ClsId, and Operation are required for SendMsg in: "
            + argsJson, dst, argsJson);
      }

      final Object argsInner = argsMap.get("Args");
      if (argsInner instanceof JsonMap)
      {
        for (final Map.Entry<String, Object> e : ((JsonMap) argsInner).entrySet())
        {
          final Object v = e.getValue();
          body.addAttr(e.getKey(), v != null ? v.toString() : "");
        }
      }
      else
      {
        // Fallback: the LLM may have placed operation params at the top level
        // instead of nesting them inside Args. Include any key that is not a
        // SendMsg envelope field in the body.
        for (final Map.Entry<String, Object> e : argsMap.entrySet())
        {
          final String k = e.getKey();
          if ("HstId".equals(k) || "AppId".equals(k) || "ActId".equals(k)
              || "ObjId".equals(k) || "ClsAppId".equals(k) || "ClsId".equals(k)
              || "Operation".equals(k) || "_target".equals(k))
            continue;
          final Object v = e.getValue();
          body.addAttr(k, v != null ? v.toString() : "");
        }
      }
    }

    return dispatch(callerDomId, callerCtx, dst, clsAppId, clsId,
                    operation, body, argsJson, msgClient);
  }

  // -----------------------------------------------------------------------
  // Shared send/parse tail
  // -----------------------------------------------------------------------

  private static Result dispatch(final DomId            callerDomId,
                                  final Context          callerCtx,
                                  final DomId            dst,
                                  final String           clsAppId,
                                  final String           clsId,
                                  final String           operation,
                                  final ObjAttrs         body,
                                  final String           argsJson,
                                  final DomatarMsgClient msgClient)
      throws DomatarException
  {
    final JsonMsg req = new JsonMsg();
    req.addRequestHead(callerDomId, dst, callerCtx);
    req.addClsId(clsAppId, clsId);
    req.addRequestBody(operation, body);

    final JsonMsg resp = msgClient.send(dst, req);

    if (resp.isSuccess())
      return new Result("ok",  Json.toJson(resp.getAttrs().toMap()), dst, argsJson);
    else
      return new Result("error",
          resp.getErrorMsg() != null ? resp.getErrorMsg() : "call failed",
          dst, argsJson);
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  /**
   * Extract the _target JsonMap from args, supporting both the nested form
   * { "_target": { "HstId":..., ... } } and the flat form where the model
   * has hoisted the DomId fields to the top level.
   */
  private static JsonMap extractTargetMap(final JsonMap argsMap)
  {
    final Object targetObj = argsMap.get("_target");

    if (targetObj instanceof JsonMap)
      return (JsonMap) targetObj;

    if (argsMap.getString("HstId") != null
        && argsMap.getString("AppId") != null
        && argsMap.getString("ActId") != null
        && argsMap.getString("ObjId") != null)
    {
      final JsonMap m = new JsonHashMap(4);
      m.put("HstId", argsMap.getString("HstId"));
      m.put("AppId", argsMap.getString("AppId"));
      m.put("ActId", argsMap.getString("ActId"));
      m.put("ObjId", argsMap.getString("ObjId"));
      return m;
    }

    return null;
  }

  private static Result missingTarget(final String argsJson)
  {
    return new Result("error",
        "_target is missing HstId/AppId/ActId in: " + argsJson,
        null, argsJson);
  }

  private static Result missingObjId(final String argsJson)
  {
    return new Result("error",
        "_target ObjId is required in: " + argsJson,
        null, argsJson);
  }

  /**
   * Models often omit ObjId even when schema defaults are set; fill known
   * singleton targets from the tool name (e.g. conversations container).
   */
  private static String resolveObjId(final String clsAppId,
                                      final String clsId,
                                      final String msgName,
                                      final String objId,
                                      final String actId)
  {
    if (objId != null && !objId.isEmpty())
      return objId;

    if ("aiagent".equals(clsAppId) && "conversations".equals(clsId)
        && "ListConversations".equals(msgName))
      return "conversations";

    return objId;
  }
}
