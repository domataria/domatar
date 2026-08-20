/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Install routine for the Domatar core service and class descriptors
 * (Spec-Service.txt, Spec-Class.txt PART 5).
 *
 * For each platform class, creates a service descriptor (SrvAppId/SrvId)
 * plus a slim class descriptor (Implements, MsgPolicy only).  Platform
 * classes covered:
 *
 *   (hst,     hsts)          - host directory service
 *   (act,     actManager)    - account manager
 *   (act,     actCache)      - per-prv cached account record
 *   (domatar, cls)           - class meta-class (self-describing)
 *   (domatar, app)           - application node in the Navigator tree
 *   (domatar, clss)          - Classes container
 *   (domatar, hosts)         - host list
 *   (domatar, host)          - individual host
 *   (domatar, catalog)       - app catalog container
 *   (domatar, catalogEntry)  - one catalog entry
 *   (domatar, srv)           - service meta-class (self-describing)
 *   (domatar, srvs)          - Services container
 *
 * All objects live on the navigator~&lt;actId&gt; sub-host (created first by
 * NavigatorInstall) under appId="domatar".
 *
 * Called from ActManagerImpl.addAct immediately after NavigatorInstall.
 * Every step is idempotent: a second call is a no-op.
 */
public class DomatarInstall
{
  /**
   * Standard per-user call: creates core class descriptors on
   * navigator-&lt;actId&gt;-&lt;prvId&gt;.
   * Called from ActManagerImpl.addAct immediately after NavigatorInstall.
   */
  public static void install(final String actId, final String prvId) throws DomatarException
  {
    final String navHstId = (prvId != null && !prvId.isEmpty())
        ? DomId.subHstId("navigator", actId, prvId)
        : DomId.subHstId("navigator", actId);

    installOnBase(new DomId(navHstId, "domatar", actId, ""));
  }

  /** @deprecated prefer {@link #install(String, String)} with prvId */
  public static void install(final String actId) throws DomatarException
  {
    install(actId, com.domatar.core.DomatarConfig.getPrvId());
  }

  /**
   * Creates (or refreshes) all core Domatar class descriptors on the sub-host
   * identified by baseId.  Used by DomatarProviderInstall to populate the
   * provider's domatar-<prvActId> sub-host with an independent copy (Spec-DomatarApp.txt
   * PART 8, principle P2).
   */
  public static void installOnBase(final DomId baseId) throws DomatarException
  {
    // clss and srvs container objects (no app link — links are per app install).
    ClsInstall.ensureClssObj(baseId, "Core Domatar class descriptors");
    SrvInstall.ensureSrvsObj(baseId, "Core Domatar service descriptors");

    // For each platform class: addSrvObj (interface only) + upsertClsImplementing (slim).

    SrvInstall.addSrvObj(baseId, "hst", "hsts",
        "Host directory service for registration and lookup",
        hstHstsSrvJson());
    ClsInstall.upsertClsImplementing(baseId, "hst", "hsts",
        "Host directory service for registration and lookup",
        hstHstsClsJson());

    SrvInstall.addSrvObj(baseId, "act", "actManager",
        "Central account manager for sign-up, login, and identity",
        actManagerSrvJson());
    ClsInstall.upsertClsImplementing(baseId, "act", "actManager",
        "Central account manager for sign-up, login, and identity",
        actManagerClsJson());

    SrvInstall.addSrvObj(baseId, "act", "actCache",
        "Per-prv cached copy of a user account record",
        actCacheSrvJson());
    ClsInstall.upsertClsImplementing(baseId, "act", "actCache",
        "Per-prv cached copy of a user account record",
        actCacheClsJson());

    // (domatar, cls) is self-describing: it implements its own service.
    SrvInstall.addSrvObj(baseId, "domatar", "cls",
        "Describes the attributes and messages of a Domatar class",
        domatarClsSrvJson());
    ClsInstall.upsertClsImplementing(baseId, "domatar", "cls",
        "Describes the attributes and messages of a Domatar class",
        domatarClsClsJson());

    SrvInstall.addSrvObj(baseId, "domatar", "app",
        "An installed application node in the Navigator tree",
        domatarAppSrvJson());
    ClsInstall.upsertClsImplementing(baseId, "domatar", "app",
        "An installed application node in the Navigator tree",
        domatarAppClsJson());

    SrvInstall.addSrvObj(baseId, "domatar", "clss",
        "Container listing all class descriptors for an application",
        domatarClssSrvJson());
    ClsInstall.upsertClsImplementing(baseId, "domatar", "clss",
        "Container listing all class descriptors for an application",
        domatarClssClsJson());

    SrvInstall.addSrvObj(baseId, "domatar", "hosts",
        "Container of all hosts registered on this provider",
        domatarHostsSrvJson());
    ClsInstall.upsertClsImplementing(baseId, "domatar", "hosts",
        "Container of all hosts registered on this provider",
        domatarHostsClsJson());

    SrvInstall.addSrvObj(baseId, "domatar", "host",
        "An individual registered host",
        domatarHostSrvJson());
    ClsInstall.upsertClsImplementing(baseId, "domatar", "host",
        "An individual registered host",
        domatarHostClsJson());

    SrvInstall.addSrvObj(baseId, "domatar", "catalog",
        "Container of all applications installed on this provider",
        domatarCatalogSrvJson());
    ClsInstall.upsertClsImplementing(baseId, "domatar", "catalog",
        "Container of all applications installed on this provider",
        domatarCatalogClsJson());

    SrvInstall.addSrvObj(baseId, "domatar", "catalogEntry",
        "An entry in the app catalog representing one installed application",
        domatarCatalogEntrySrvJson());
    ClsInstall.upsertClsImplementing(baseId, "domatar", "catalogEntry",
        "An entry in the app catalog representing one installed application",
        domatarCatalogEntryClsJson());

    // (domatar, srv) is self-describing: it implements its own service.
    SrvInstall.addSrvObj(baseId, "domatar", "srv",
        "Describes a Domatar service interface",
        domatarSrvSrvJson());
    ClsInstall.upsertClsImplementing(baseId, "domatar", "srv",
        "Describes a Domatar service interface",
        domatarSrvClsJson());

    SrvInstall.addSrvObj(baseId, "domatar", "srvs",
        "Container listing all service descriptors for an application",
        domatarSrvsSrvJson());
    ClsInstall.upsertClsImplementing(baseId, "domatar", "srvs",
        "Container listing all service descriptors for an application",
        domatarSrvsClsJson());
  }

  // -------------------------------------------------------------------------
  // Service-definition JSON builders (interface only — no SideEffect/Auth)
  // -------------------------------------------------------------------------

  private static String hstHstsSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"hst\","
        + "\"SrvId\":\"hsts\","
        + "\"Attrs\":[],"
        + "\"Msgs\":["
        +   "{"
        +     "\"Name\":\"GetHst\","
        +     "\"Type\":{\"HstId\":\"String\",\"Domain\":\"String\","
        +              "\"PrvId\":\"String\",\"Version\":\"String\"},"
        +     "\"Parms\":[{\"Name\":\"HstId\",\"Type\":\"String\"}]"
        +   "},"
        +   "{"
        +     "\"Name\":\"UpdateHst\","
        +     "\"Type\":{},"
        +     "\"Parms\":["
        +       "{\"Name\":\"HstId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"Domain\",\"Type\":\"String\"},"
        +       "{\"Name\":\"PrvId\",\"Type\":\"String\"}"
        +     "]"
        +   "}"
        + "]}";
  }

  private static String hstHstsClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"hst\","
        + "\"ClsId\":\"hsts\","
        + "\"Implements\":[\"hst.hsts\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"hst.hsts\",\"Name\":\"GetHst\",\"SideEffect\":\"Read\"}"
        + "]}";
  }

  private static String actManagerSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"act\","
        + "\"SrvId\":\"actManager\","
        + "\"Attrs\":[],"
        + "\"Msgs\":["
        +   "{"
        +     "\"Name\":\"AddAct\","
        +     "\"Type\":{\"Token\":\"String\",\"ActId\":\"String\","
        +              "\"UsrId\":\"String\",\"UsrName\":\"String\"},"
        +     "\"Parms\":["
        +       "{\"Name\":\"ActId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"UsrId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"HstId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"Domain\",\"Type\":\"String\"},"
        +       "{\"Name\":\"PrvId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"UsrName\",\"Type\":\"String\"},"
        +       "{\"Name\":\"Pwd\",\"Type\":\"String\"},"
        +       "{\"Name\":\"Ip\",\"Type\":\"String\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"GetAct\","
        +     "\"Type\":{\"ActId\":\"String\",\"UsrId\":\"String\","
        +              "\"UsrName\":\"String\"},"
        +     "\"Parms\":["
        +       "{\"Name\":\"ActId?\",\"Type\":\"String\"},"
        +       "{\"Name\":\"UsrId?\",\"Type\":\"String\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"Login\","
        +     "\"Type\":{\"ActId\":\"String\",\"UsrId\":\"String\","
        +              "\"UsrName\":\"String\",\"Token\":\"String\"},"
        +     "\"Parms\":["
        +       "{\"Name\":\"UsrId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"Pwd\",\"Type\":\"String\"},"
        +       "{\"Name\":\"Ip\",\"Type\":\"String\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"Logout\","
        +     "\"Type\":{},"
        +     "\"Parms\":["
        +       "{\"Name\":\"UsrId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"Ip\",\"Type\":\"String\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"VerifyLogin\","
        +     "\"Type\":{\"LoggedIn\":\"String\",\"ActId?\":\"String\","
        +              "\"UsrId?\":\"String\",\"UsrName?\":\"String\"},"
        +     "\"Parms\":["
        +       "{\"Name\":\"ActId?\",\"Type\":\"String\"},"
        +       "{\"Name\":\"UsrId?\",\"Type\":\"String\"},"
        +       "{\"Name\":\"Token\",\"Type\":\"String\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"UpdateAct\","
        +     "\"Type\":{\"ActId\":\"String\",\"UsrId?\":\"String\","
        +              "\"UsrName?\":\"String\"},"
        +     "\"Parms\":["
        +       "{\"Name\":\"ActId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"UsrId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"NewLocalname?\",\"Type\":\"String\"},"
        +       "{\"Name\":\"NewUsrName?\",\"Type\":\"String\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"ChangePwd\","
        +     "\"Type\":{\"Changed\":\"String\"},"
        +     "\"Parms\":["
        +       "{\"Name\":\"ActId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"UsrId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"OldPwd\",\"Type\":\"String\"},"
        +       "{\"Name\":\"NewPwd\",\"Type\":\"String\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"DeleteAct\","
        +     "\"Type\":{},"
        +     "\"Parms\":["
        +       "{\"Name\":\"ActId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"UsrId\",\"Type\":\"String\"}"
        +     "]"
        +   "}"
        + "]}";
  }

  private static String actManagerClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"act\","
        + "\"ClsId\":\"actManager\","
        + "\"Implements\":[\"act.actManager\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"act.actManager\",\"Name\":\"GetAct\",\"SideEffect\":\"Read\"},"
        +   "{\"Srv\":\"act.actManager\",\"Name\":\"VerifyLogin\",\"SideEffect\":\"Read\"},"
        +   "{\"Srv\":\"act.actManager\",\"Name\":\"DeleteAct\",\"SideEffect\":\"Destructive\"}"
        + "]}";
  }

  private static String actCacheSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"act\","
        + "\"SrvId\":\"actCache\","
        + "\"Attrs\":["
        +   "{\"Name\":\"UsrId\",\"Type\":\"String\"},"
        +   "{\"Name\":\"UsrName\",\"Type\":\"String\"}"
        + "],"
        + "\"Msgs\":["
        +   "{"
        +     "\"Name\":\"GetAct\","
        +     "\"Type\":{\"UsrId\":\"String\",\"UsrName\":\"String\"},"
        +     "\"Parms\":[]"
        +   "}"
        + "]}";
  }

  private static String actCacheClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"act\","
        + "\"ClsId\":\"actCache\","
        + "\"Implements\":[\"act.actCache\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"act.actCache\",\"Name\":\"GetAct\",\"SideEffect\":\"Read\"}"
        + "]}";
  }

  private static String domatarAppSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"domatar\","
        + "\"SrvId\":\"app\","
        + "\"Attrs\":[],"
        + "\"Msgs\":["
        +   "{"
        +     "\"Name\":\"GetObj\","
        +     "\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"},"
        +     "\"Parms\":[]"
        +   "},"
        +   "{"
        +     "\"Name\":\"Open\","
        +     "\"Type\":[{\"Name\":\"String\",\"Desc\":\"String\","
        +               "\"DomId\":\"String\",\"ClsAppId\":\"String\","
        +               "\"ClsId\":\"String\"}],"
        +     "\"Parms\":[]"
        +   "}"
        + "]}";
  }

  private static String domatarAppClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"domatar\","
        + "\"ClsId\":\"app\","
        + "\"Implements\":[\"domatar.app\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"domatar.app\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"},"
        +   "{\"Srv\":\"domatar.app\",\"Name\":\"Open\",\"SideEffect\":\"Read\"}"
        + "]}";
  }

  private static String domatarClssSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"domatar\","
        + "\"SrvId\":\"clss\","
        + "\"Attrs\":[],"
        + "\"Msgs\":["
        +   "{"
        +     "\"Name\":\"GetObj\","
        +     "\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"},"
        +     "\"Parms\":[]"
        +   "},"
        +   "{"
        +     "\"Name\":\"Open\","
        +     "\"Type\":[{\"Name\":\"String\",\"Desc\":\"String\","
        +               "\"DomId\":\"String\",\"ClsAppId\":\"String\","
        +               "\"ClsId\":\"String\"}],"
        +     "\"Parms\":[]"
        +   "}"
        + "]}";
  }

  private static String domatarClssClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"domatar\","
        + "\"ClsId\":\"clss\","
        + "\"Implements\":[\"domatar.clss\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"domatar.clss\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"},"
        +   "{\"Srv\":\"domatar.clss\",\"Name\":\"Open\",\"SideEffect\":\"Read\"}"
        + "]}";
  }

  private static String domatarHostsSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"domatar\","
        + "\"SrvId\":\"hosts\","
        + "\"Attrs\":[],"
        + "\"Msgs\":["
        +   "{\"Name\":\"GetObj\","
        +    "\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"},"
        +    "\"Parms\":[]},"
        +   "{\"Name\":\"Open\","
        +    "\"Type\":[{\"Name\":\"String\",\"Desc\":\"String\","
        +               "\"DomId\":\"String\",\"ClsAppId\":\"String\",\"ClsId\":\"String\"}],"
        +    "\"Parms\":[]},"
        +   "{\"Name\":\"ListHsts\","
        +    "\"Type\":[{\"HstId\":\"String\",\"Domain\":\"String\","
        +               "\"PrvId\":\"String\",\"Version\":\"Number\"}],"
        +    "\"Parms\":[]},"
        +   "{\"Name\":\"RegisterHst\","
        +    "\"Type\":{\"HstId\":\"String\"},"
        +    "\"Parms\":["
        +      "{\"Name\":\"HstId\",\"Type\":\"String\"},"
        +      "{\"Name\":\"Domain\",\"Type\":\"String\"},"
        +      "{\"Name\":\"PrvId\",\"Type\":\"String\"}"
        +    "]},"
        +   "{\"Name\":\"DeregisterHst\","
        +    "\"Type\":{},"
        +    "\"Parms\":[{\"Name\":\"HstId\",\"Type\":\"String\"}]}"
        + "]}";
  }

  private static String domatarHostsClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"domatar\","
        + "\"ClsId\":\"hosts\","
        + "\"Implements\":[\"domatar.hosts\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"domatar.hosts\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"},"
        +   "{\"Srv\":\"domatar.hosts\",\"Name\":\"Open\",\"SideEffect\":\"Read\"},"
        +   "{\"Srv\":\"domatar.hosts\",\"Name\":\"ListHsts\",\"SideEffect\":\"Read\"},"
        +   "{\"Srv\":\"domatar.hosts\",\"Name\":\"DeregisterHst\",\"SideEffect\":\"Destructive\"}"
        + "]}";
  }

  private static String domatarHostSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"domatar\","
        + "\"SrvId\":\"host\","
        + "\"Attrs\":["
        +   "{\"Name\":\"HstId\",\"Type\":\"String\"},"
        +   "{\"Name\":\"Domain\",\"Type\":\"String\"},"
        +   "{\"Name\":\"PrvId\",\"Type\":\"String\"},"
        +   "{\"Name\":\"Version\",\"Type\":\"String\"}"
        + "],"
        + "\"Msgs\":["
        +   "{\"Name\":\"GetObj\","
        +    "\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"},"
        +    "\"Parms\":[]},"
        +   "{\"Name\":\"Open\","
        +    "\"Type\":[],"
        +    "\"Parms\":[]},"
        +   "{\"Name\":\"UpdateHst\","
        +    "\"Type\":{},"
        +    "\"Parms\":["
        +      "{\"Name\":\"Domain\",\"Type\":\"String?\"},"
        +      "{\"Name\":\"PrvId\",\"Type\":\"String?\"}"
        +    "]}"
        + "]}";
  }

  private static String domatarHostClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"domatar\","
        + "\"ClsId\":\"host\","
        + "\"Implements\":[\"domatar.host\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"domatar.host\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"},"
        +   "{\"Srv\":\"domatar.host\",\"Name\":\"Open\",\"SideEffect\":\"Read\"}"
        + "]}";
  }

  private static String domatarCatalogSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"domatar\","
        + "\"SrvId\":\"catalog\","
        + "\"Attrs\":[],"
        + "\"Msgs\":["
        +   "{"
        +     "\"Name\":\"GetCatalog\","
        +     "\"Type\":{"
        +       "\"Apps\":[{"
        +         "\"AppId\":\"String\","
        +         "\"AppName\":\"String\","
        +         "\"AppDesc\":\"String\","
        +         "\"Version\":\"String\","
        +         "\"IsDefault\":\"String\""
        +       "}]"
        +     "},"
        +     "\"Parms\":[]"
        +   "},"
        +   "{"
        +     "\"Name\":\"RegisterApp\","
        +     "\"Type\":{},"
        +     "\"Parms\":["
        +       "{\"Name\":\"AppId\",\"Type\":\"String\"},"
        +       "{\"Name\":\"AppName\",\"Type\":\"String\"},"
        +       "{\"Name\":\"AppDesc\",\"Type\":\"String\"},"
        +       "{\"Name\":\"Version\",\"Type\":\"String\"}"
        +     "]"
        +   "}"
        + "]}";
  }

  private static String domatarCatalogClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"domatar\","
        + "\"ClsId\":\"catalog\","
        + "\"Implements\":[\"domatar.catalog\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"domatar.catalog\",\"Name\":\"GetCatalog\",\"SideEffect\":\"Read\"}"
        + "]}";
  }

  private static String domatarCatalogEntrySrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"domatar\","
        + "\"SrvId\":\"catalogEntry\","
        + "\"Attrs\":["
        +   "{\"Name\":\"AppId\",\"Type\":\"String\"},"
        +   "{\"Name\":\"AppName\",\"Type\":\"String\"},"
        +   "{\"Name\":\"AppDesc\",\"Type\":\"String\"},"
        +   "{\"Name\":\"Version\",\"Type\":\"String\"}"
        + "],"
        + "\"Msgs\":["
        +   "{"
        +     "\"Name\":\"GetObj\","
        +     "\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"},"
        +     "\"Parms\":[]"
        +   "}"
        + "]}";
  }

  private static String domatarCatalogEntryClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"domatar\","
        + "\"ClsId\":\"catalogEntry\","
        + "\"Implements\":[\"domatar.catalogEntry\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"domatar.catalogEntry\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"}"
        + "]}";
  }

  /**
   * Service self-description of the (domatar, cls) meta-class (Spec-Class.txt PART 8).
   * SideEffect/Auth are stripped here; they live in domatarClsClsJson() MsgPolicy.
   */
  private static String domatarClsSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"domatar\","
        + "\"SrvId\":\"cls\","
        + "\"Description\":\"A class descriptor object. Each installed application"
        +   " creates one instance per class it uses, storing the class schema as a"
        +   " persistent object. Self-describing: this object describes"
        +   " class-descriptor objects.\","
        + "\"Conventions\":{"
        +   "\"ObjId\":\"<ClsId>Cls — Cls suffix distinguishes descriptors from"
        +     " same-named data objects\","
        +   "\"ObjName\":\"Same as ObjId\","
        +   "\"Owner\":\"The installing application on this sub-host\","
        +   "\"Lifecycle\":\"Created idempotently at install time; updated when the"
        +     " descriptor schema evolves\","
        +   "\"Containment\":\"Child of the app's (domatar, clss) Classes container\""
        + "},"
        + "\"Attrs\":["
        +   "{\"Name\":\"ClsAppId\",\"Type\":\"String\","
        +    "\"Description\":\"The application namespace that defines the described class.\"},"
        +   "{\"Name\":\"ClsId\",\"Type\":\"String\","
        +    "\"Description\":\"The class identifier within ClsAppId's namespace.\"},"
        +   "{\"Name\":\"Description\",\"Type\":\"String\","
        +    "\"Description\":\"Optional multi-line prose summary of the described class.\"},"
        +   "{\"Name\":\"Conventions\",\"Type\":\"String\","
        +    "\"Description\":\"Optional JSON object with ObjId/ObjName/Owner/Lifecycle/Containment hints.\"},"
        +   "{\"Name\":\"Attrs\","
        +    "\"Type\":[{\"Name\":\"String\",\"Type\":\"String\",\"Description\":\"String\"}],"
        +    "\"Description\":\"Array of attribute definitions.\"},"
        +   "{\"Name\":\"Msgs\","
        +    "\"Type\":[{\"Name\":\"String\",\"Description\":\"String\","
        +               "\"Type\":\"String\","
        +               "\"Parms\":[{\"Name\":\"String\",\"Type\":\"String\",\"Description\":\"String\"}]}],"
        +    "\"Description\":\"Array of message definitions.\"}"
        + "],"
        + "\"Msgs\":["
        +   "{"
        +     "\"Name\":\"GetObj\","
        +     "\"Description\":\"Return the full class descriptor for this object.\","
        +     "\"Type\":{"
        +       "\"ClsAppId\":\"String\","
        +       "\"ClsId\":\"String\","
        +       "\"Description?\":\"String\","
        +       "\"Conventions?\":\"String\","
        +       "\"Attrs\":[{\"Name\":\"String\",\"Type\":\"String\",\"Description?\":\"String\"}],"
        +       "\"Msgs\":[{\"Name\":\"String\",\"Description?\":\"String\","
        +                 "\"Type\":\"String\","
        +                 "\"Parms\":[{\"Name\":\"String\",\"Type\":\"String\",\"Description?\":\"String\"}]}]"
        +     "},"
        +     "\"Parms\":[]"
        +   "},"
        +   "{"
        +     "\"Name\":\"GetCls\","
        +     "\"Description\":\"Return the class descriptor for the (ClsAppId, ClsId) pair on this host,"
        +       " looked up by the ObjId convention <ClsId>Cls.\","
        +     "\"Type\":{"
        +       "\"ClsAppId\":\"String\","
        +       "\"ClsId\":\"String\","
        +       "\"ObjName\":\"String\","
        +       "\"ObjDesc\":\"String\","
        +       "\"Attrs\":{}"
        +     "},"
        +     "\"Parms\":["
        +       "{\"Name\":\"ClsAppId\",\"Type\":\"String\","
        +        "\"Description\":\"The defining application's appId.\"},"
        +       "{\"Name\":\"ClsId\",\"Type\":\"String\","
        +        "\"Description\":\"The class identifier within ClsAppId's namespace.\"}"
        +     "]"
        +   "}"
        + "]}";
  }

  private static String domatarClsClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"domatar\","
        + "\"ClsId\":\"cls\","
        + "\"Implements\":[\"domatar.cls\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"domatar.cls\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\",\"Auth\":\"Public\"},"
        +   "{\"Srv\":\"domatar.cls\",\"Name\":\"GetCls\",\"SideEffect\":\"Read\",\"Auth\":\"Public\"}"
        + "]}";
  }

  /**
   * Service self-description of the (domatar, srv) meta-class (Spec-Service.txt PART 5).
   * SideEffect/Auth stripped; live in domatarSrvClsJson() MsgPolicy.
   */
  private static String domatarSrvSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"domatar\","
        + "\"SrvId\":\"srv\","
        + "\"Description\":\"A service descriptor object. Each application creates one instance"
        +   " per service it defines, storing the service interface as a persistent object."
        +   " Services are immutable once published.\","
        + "\"Conventions\":{"
        +   "\"ObjId\":\"<SrvId>Srv — Srv suffix distinguishes descriptors from data objects\","
        +   "\"ObjAppId\":\"Equal to SrvAppId (the defining application's namespace)\","
        +   "\"Lifecycle\":\"Created once at install time; never modified (immutable)\","
        +   "\"Containment\":\"Child of the app's (domatar, srvs) Services container\""
        + "},"
        + "\"Attrs\":["
        +   "{\"Name\":\"SrvAppId\",\"Type\":\"String\","
        +    "\"Description\":\"The application namespace that defines this service.\"},"
        +   "{\"Name\":\"SrvId\",\"Type\":\"String\","
        +    "\"Description\":\"The service identifier within SrvAppId's namespace.\"},"
        +   "{\"Name\":\"Description\",\"Type\":\"String\","
        +    "\"Description\":\"Optional prose description of the service interface.\"},"
        +   "{\"Name\":\"Extends\","
        +    "\"Type\":[\"String\"],"
        +    "\"Description\":\"Optional list of \\\"srvAppId.srvId\\\" strings for parent services.\"},"
        +   "{\"Name\":\"Attrs\","
        +    "\"Type\":[{\"Name\":\"String\",\"Type\":\"String\",\"Description\":\"String\"}],"
        +    "\"Description\":\"Array of attribute definitions for this service interface.\"},"
        +   "{\"Name\":\"Msgs\","
        +    "\"Type\":[{\"Name\":\"String\",\"Description\":\"String\","
        +               "\"Type\":\"String\","
        +               "\"Parms\":[{\"Name\":\"String\",\"Type\":\"String\",\"Description\":\"String\"}]}],"
        +    "\"Description\":\"Array of message definitions (no SideEffect or Auth — those belong to the class).\"}"
        + "],"
        + "\"Msgs\":["
        +   "{"
        +     "\"Name\":\"GetSrv\","
        +     "\"Description\":\"Return the service descriptor for the (SrvAppId, SrvId) pair on this host,"
        +       " looked up by the ObjId convention <SrvId>Srv with obj AppId = SrvAppId.\","
        +     "\"Type\":{"
        +       "\"SrvAppId\":\"String\","
        +       "\"SrvId\":\"String\","
        +       "\"ObjName\":\"String\","
        +       "\"ObjDesc\":\"String\","
        +       "\"Attrs\":{}"
        +     "},"
        +     "\"Parms\":["
        +       "{\"Name\":\"SrvAppId\",\"Type\":\"String\","
        +        "\"Description\":\"The defining application's appId.\"},"
        +       "{\"Name\":\"SrvId\",\"Type\":\"String\","
        +        "\"Description\":\"The service identifier within SrvAppId's namespace.\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"GetObj\","
        +     "\"Description\":\"Return the raw service descriptor row for this object.\","
        +     "\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"},"
        +     "\"Parms\":[]"
        +   "}"
        + "]}";
  }

  private static String domatarSrvClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"domatar\","
        + "\"ClsId\":\"srv\","
        + "\"Implements\":[\"domatar.srv\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"domatar.srv\",\"Name\":\"GetSrv\",\"SideEffect\":\"Read\",\"Auth\":\"Public\"},"
        +   "{\"Srv\":\"domatar.srv\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\",\"Auth\":\"Public\"}"
        + "]}";
  }

  private static String domatarSrvsSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"domatar\","
        + "\"SrvId\":\"srvs\","
        + "\"Attrs\":[],"
        + "\"Msgs\":["
        +   "{"
        +     "\"Name\":\"GetObj\","
        +     "\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"},"
        +     "\"Parms\":[]"
        +   "},"
        +   "{"
        +     "\"Name\":\"Open\","
        +     "\"Type\":[{\"Name\":\"String\",\"Desc\":\"String\","
        +               "\"DomId\":\"String\",\"ClsAppId\":\"String\","
        +               "\"ClsId\":\"String\"}],"
        +     "\"Parms\":[]"
        +   "}"
        + "]}";
  }

  private static String domatarSrvsClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"domatar\","
        + "\"ClsId\":\"srvs\","
        + "\"Implements\":[\"domatar.srvs\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"domatar.srvs\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\"},"
        +   "{\"Srv\":\"domatar.srvs\",\"Name\":\"Open\",\"SideEffect\":\"Read\"}"
        + "]}";
  }
}
