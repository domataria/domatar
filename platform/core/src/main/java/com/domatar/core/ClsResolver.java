/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.domatar.db.ObjDb;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.Obj;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Resolves a class descriptor into its merged interface (Spec-Service.txt PART 11).
 *
 * Resolution is LOCAL: it reads service objects via ObjDb on the same host as
 * the class descriptor; no cross-host messaging is needed.
 *
 * Legacy inline descriptors (no "Implements" field) pass through unchanged, so
 * all un-migrated apps continue to work without modification.
 *
 * Plain-text descriptors (apps whose "Msgs" and "SideEffect" are plain strings)
 * pass through both the service's string fields and the class's top-level
 * "SideEffect" / "Auth" strings to the resolved document, so the AI Agent's
 * MsgsSchemaBuilder continues to find them at the expected keys.
 *
 * Thread-safety: stateless; all state is in ClsMap (synchronized) and ObjDb.
 */
public class ClsResolver
{
  private static final String DEFAULT_SIDE_EFFECT = "Write";
  private static final String DEFAULT_AUTH        = "Verified";

  /**
   * Resolve the class-definition document for the class descriptor at clsDescDomId.
   *
   * Returns the JSON string to be placed under "Attrs" in the GetCls response,
   * or null if the class descriptor object does not exist.
   *
   * Uses and populates ClsMap. Never throws for malformed data; falls back to
   * the stored document when parsing fails.
   *
   * @param clsDescDomId  DomId of the class descriptor object
   *                      (hstId, clsAppId, actId, clsId + "Cls").
   * @param clsAppId      The ClsAppId component (for cache keying and output).
   * @param clsId         The ClsId component (for cache keying and output).
   */
  public static String resolve(DomId  clsDescDomId,
                                String clsAppId,
                                String clsId) throws DomatarException
  {
    String cached = ClsMap.get(clsAppId, clsId);
    if (cached != null)
      return cached;

    Obj clsObj = ObjDb.getObj(clsDescDomId);
    if (clsObj == null)
      return null;

    String stored = clsObj.attrs.toString();

    JsonMap clsDoc;
    try
    {
      clsDoc = Json.parseMap(stored);
    }
    catch (Exception e)
    {
      // Unparseable Attrs: treat as opaque legacy, cache and return as-is.
      ClsMap.put(clsAppId, clsId, stored);
      return stored;
    }

    // Legacy passthrough: a descriptor without "Implements" is an inline class.
    Object implRaw = clsDoc.get("Implements");
    if (!(implRaw instanceof JsonList) || ((JsonList) implRaw).isEmpty())
    {
      ClsMap.put(clsAppId, clsId, stored);
      return stored;
    }

    // Full resolution: flatten each implemented service (including Extends).
    JsonList     implements_ = (JsonList) implRaw;
    JsonList     mergedAttrs = new JsonArrayList();
    JsonList     mergedMsgs  = new JsonArrayList();
    Set<String>  attrSeen    = new HashSet<>();
    Set<String>  msgSeen     = new HashSet<>();
    List<String> closure     = new ArrayList<>();
    JsonHashMap  plaintextExtras = new JsonHashMap();

    for (Object ref : implements_)
    {
      if (ref instanceof String)
        flattenService((String) ref, clsDescDomId,
                       mergedAttrs, mergedMsgs,
                       attrSeen, msgSeen, closure,
                       plaintextExtras);
    }

    applyAttrStorage(mergedAttrs, clsDoc);
    applyMsgPolicy(mergedMsgs, clsDoc);

    // Build the resolved document.
    JsonHashMap resolvedDoc = new JsonHashMap();
    resolvedDoc.put("ClsAppId", clsAppId);
    resolvedDoc.put("ClsId",    clsId);

    Object desc = clsDoc.get("Description");
    if (desc != null)
      resolvedDoc.put("Description", desc);

    Object conv = clsDoc.get("Conventions");
    if (conv != null)
      resolvedDoc.put("Conventions", conv);

    resolvedDoc.put("Attrs", mergedAttrs);
    resolvedDoc.put("Msgs",  mergedMsgs);

    // Plain-text service passthrough: copy string-typed Msgs/Attrs from services
    // and SideEffect/Auth strings from the class (Spec-Service.txt T1.8 simpler path).
    for (String key : plaintextExtras.keySet())
      resolvedDoc.put(key, plaintextExtras.get(key));

    copyStringIfPresent(clsDoc, resolvedDoc, "SideEffect");
    copyStringIfPresent(clsDoc, resolvedDoc, "Auth");

    JsonArrayList closureList = new JsonArrayList();
    closureList.addAll(closure);
    resolvedDoc.put("ImplementsClosure", closureList);

    String resolvedJson = Json.toJson(resolvedDoc);
    ClsMap.put(clsAppId, clsId, resolvedJson);
    return resolvedJson;
  }

  /**
   * Compensates MsgName for {@code msgName} on a resolved GetCls Attrs map,
   * or null if absent / empty (irreversible).
   */
  public static String compensates(final JsonMap mergedMsgListHolder,
      final String msgName)
  {
    if (mergedMsgListHolder == null || msgName == null || msgName.isEmpty())
      return null;

    final Object msgsRaw = mergedMsgListHolder.get("Msgs");
    if (!(msgsRaw instanceof JsonList))
      return null;

    for (final Object m : (JsonList) msgsRaw)
    {
      if (!(m instanceof JsonMap))
        continue;
      final JsonMap msg = (JsonMap) m;
      if (!msgName.equals(msg.getString("Name")))
        continue;
      final String compensates = msg.getString("Compensates");
      if (compensates == null || compensates.isEmpty())
        return null;
      return compensates;
    }
    return null;
  }

  /**
   * Compensates MsgName for a class, via ClsMap then local resolve.
   * Unknown class or missing field → null (irreversible).
   */
  public static String compensates(final String clsAppId, final String clsId,
      final String msgName, final DomId clsObjHint) throws DomatarException
  {
    if (msgName == null || msgName.isEmpty())
      return null;

    String json = null;
    if (clsAppId != null && clsId != null)
      json = ClsMap.get(clsAppId, clsId);

    if (json == null && clsObjHint != null)
      json = resolve(clsObjHint, clsAppId, clsId);

    if (json == null)
      return null;

    final JsonMap doc;
    try
    {
      doc = Json.parseMap(json);
    }
    catch (final Exception e)
    {
      return null;
    }
    return compensates(doc, msgName);
  }

  // ---------------------------------------------------------------------------
  // Service flattening (recursive; handles Extends diamond dedup)
  // ---------------------------------------------------------------------------

  /**
   * Recursively flatten one service ref ("srvAppId.srvId") into mergedAttrs /
   * mergedMsgs, tagging every member with "Srv":"srvAppId.srvId".
   *
   * Extends ancestors are flattened first so base members appear before derived
   * members and keep their defining-service identity.
   *
   * srvAppId.srvId is assumed to contain exactly one dot with no dot in either
   * segment — true for all current app and service ids.
   *
   * Plain-text services (whose "Msgs" field is a String) do not contribute to
   * the structured mergedMsgs/mergedAttrs lists; instead their string fields are
   * accumulated in plaintextExtras for the caller to merge at the top level.
   */
  private static void flattenService(String       ref,
                                      DomId        clsDescDomId,
                                      JsonList     mergedAttrs,
                                      JsonList     mergedMsgs,
                                      Set<String>  attrSeen,
                                      Set<String>  msgSeen,
                                      List<String> closure,
                                      JsonHashMap  plaintextExtras)
      throws DomatarException
  {
    if (closure.contains(ref))
      return;
    closure.add(ref);

    int dot = ref.indexOf('.');
    if (dot <= 0 || dot >= ref.length() - 1)
      return;

    String srvAppId = ref.substring(0, dot);
    String srvId    = ref.substring(dot + 1);

    DomId srvDomId = new DomId(clsDescDomId.hstId, srvAppId,
                                clsDescDomId.actId, srvId + "Srv");
    Obj srvObj = ObjDb.getObj(srvDomId);
    if (srvObj == null)
      return;

    JsonMap srvDoc;
    try
    {
      srvDoc = Json.parseMap(srvObj.attrs.toString());
    }
    catch (Exception e)
    {
      return;
    }

    // Recurse into Extends first so base members precede derived members.
    Object extendsRaw = srvDoc.get("Extends");
    if (extendsRaw instanceof JsonList)
    {
      for (Object ext : (JsonList) extendsRaw)
      {
        if (ext instanceof String)
          flattenService((String) ext, clsDescDomId,
                         mergedAttrs, mergedMsgs,
                         attrSeen, msgSeen, closure,
                         plaintextExtras);
      }
    }

    // Plain-text service: accumulate string fields and return without structured entries.
    Object msgsRaw = srvDoc.get("Msgs");
    if (msgsRaw instanceof String)
    {
      if (!plaintextExtras.containsKey("Msgs"))
        plaintextExtras.put("Msgs", msgsRaw);
      Object attrsRaw = srvDoc.get("Attrs");
      if (attrsRaw instanceof String && !plaintextExtras.containsKey("Attrs"))
        plaintextExtras.put("Attrs", attrsRaw);
      return;
    }

    // Structured service: add Attrs entries tagged with this service's identity.
    Object attrsRaw = srvDoc.get("Attrs");
    if (attrsRaw instanceof JsonList)
    {
      for (Object a : (JsonList) attrsRaw)
      {
        if (!(a instanceof JsonMap))
          continue;
        JsonMap attr = (JsonMap) a;
        String  name = attr.getString("Name");
        if (name == null || name.isEmpty())
          continue;
        if (!attrSeen.add(ref + "." + name))
          continue;
        JsonHashMap merged = new JsonHashMap();
        merged.putAll(attr);
        merged.put("Srv", ref);
        mergedAttrs.add(merged);
      }
    }

    // Structured service: add Msgs entries tagged with this service's identity.
    if (msgsRaw instanceof JsonList)
    {
      for (Object m : (JsonList) msgsRaw)
      {
        if (!(m instanceof JsonMap))
          continue;
        JsonMap msg  = (JsonMap) m;
        String  name = msg.getString("Name");
        if (name == null || name.isEmpty())
          continue;
        if (!msgSeen.add(ref + "." + name))
          continue;
        JsonHashMap merged = new JsonHashMap();
        merged.putAll(msg);
        merged.put("Srv", ref);
        mergedMsgs.add(merged);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Policy application
  // ---------------------------------------------------------------------------

  /**
   * For each merged attribute, set "StorageKey" from clsDoc.AttrStorage
   * matching {Srv, Name}; default is the bare Name.
   */
  private static void applyAttrStorage(JsonList mergedAttrs, JsonMap clsDoc)
  {
    Object storageRaw = clsDoc.get("AttrStorage");
    JsonList storage = (storageRaw instanceof JsonList) ? (JsonList) storageRaw : null;

    for (Object a : mergedAttrs)
    {
      if (!(a instanceof JsonHashMap))
        continue;
      JsonHashMap attr = (JsonHashMap) a;
      String name = attr.getString("Name");
      String srv  = attr.getString("Srv");
      String key  = findStorageKey(storage, srv, name);
      attr.put("StorageKey", key != null ? key : name);
    }
  }

  private static String findStorageKey(JsonList storage, String srv, String name)
  {
    if (storage == null || name == null)
      return null;

    for (Object e : storage)
    {
      if (!(e instanceof JsonMap))
        continue;
      JsonMap entry = (JsonMap) e;
      if (name.equals(entry.getString("Name"))
          && (srv == null || srv.equals(entry.getString("Srv"))))
        return entry.getString("StorageKey");
    }

    return null;
  }

  /**
   * For each merged message, set "SideEffect" and "Auth" from clsDoc.MsgPolicy
   * matching {Srv, Name}. Class policy wins; otherwise keep the service's
   * declared value; otherwise default to "Write" / "Verified".
   * Compensates is copied the same way but has no default: absent or empty
   * omits the key (irreversible).
   */
  static void applyMsgPolicy(JsonList mergedMsgs, JsonMap clsDoc)
  {
    Object policyRaw = clsDoc.get("MsgPolicy");
    JsonList policy = (policyRaw instanceof JsonList) ? (JsonList) policyRaw : null;

    for (Object m : mergedMsgs)
    {
      if (!(m instanceof JsonHashMap))
        continue;
      JsonHashMap msg  = (JsonHashMap) m;
      String      name = msg.getString("Name");
      String      srv  = msg.getString("Srv");

      JsonMap entry = findPolicyEntry(policy, srv, name);

      String sideEffect = (entry != null) ? entry.getString("SideEffect") : null;
      String auth       = (entry != null) ? entry.getString("Auth")       : null;
      String compensates = (entry != null) ? entry.getString("Compensates") : null;

      msg.put("SideEffect", pickDeclared(sideEffect, msg.getString("SideEffect"),
                                          DEFAULT_SIDE_EFFECT));
      msg.put("Auth",       pickDeclared(auth, msg.getString("Auth"),
                                          DEFAULT_AUTH));

      final String picked = pickDeclared(compensates, msg.getString("Compensates"),
          null);
      if (picked != null)
        msg.put("Compensates", picked);
      else
        msg.remove("Compensates");
    }
  }

  /**
   * Class MsgPolicy wins. If the class has no entry, keep the value the
   * service already declared (LLM-native app services put SideEffect on
   * each Msg). Only then fall back to the conservative default.
   */
  static String pickDeclared(final String policyValue,
                              final String serviceValue,
                              final String defaultValue)
  {
    if (policyValue != null && !policyValue.isEmpty())
      return policyValue;
    if (serviceValue != null && !serviceValue.isEmpty())
      return serviceValue;
    return defaultValue;
  }

  private static JsonMap findPolicyEntry(JsonList policy, String srv, String name)
  {
    if (policy == null || name == null)
      return null;

    for (Object e : policy)
    {
      if (!(e instanceof JsonMap))
        continue;
      JsonMap entry     = (JsonMap) e;
      String  entryName = entry.getString("Name");
      String  entrySrv  = entry.getString("Srv");
      if (!name.equals(entryName))
        continue;
      if (entrySrv == null || entrySrv.equals(srv))
        return entry;
    }

    return null;
  }

  // ---------------------------------------------------------------------------

  private static void copyStringIfPresent(JsonMap src, JsonHashMap dst, String key)
  {
    Object val = src.get(key);
    if (val instanceof String && !dst.containsKey(key))
      dst.put(key, val);
  }
}
