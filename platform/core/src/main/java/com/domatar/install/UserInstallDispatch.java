/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.core.DomatarConfig;
import com.domatar.db.HstDb;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Sends **InstallUser** to the target application's WAR via {@link DomatarMsgClient}.
 *
 * <p>Local installs seed the per-user sub-host in this node's {@link HstDb}
 * before dispatch. Cross-provider installs publish the host to the directory
 * only (no local claim) so {@code msgClient.send} routes to the offering
 * provider, which creates its own hst/objs inside {@code installUser}.
 *
 * Spec-Installation.txt PART 11.6 / Pass 1.
 * Host naming: {@link UserHostIds} / Spec-AppStore PART 9.
 */
public final class UserInstallDispatch
{
  private UserInstallDispatch() {}

  /**
   * Object id placeholder for InstallUser; only {@code clsAppId}/{@code clsId}
   * and routing matter (Msg may dispatch without a DB row when class is set).
   */
  public static final String USER_INSTALL_OBJ_ID = "userInstall";

  /**
   * Public alias of {@link UserHostIds#resolve(String, String, String, String)}
   * for InstallApp collision checks (Spec-AppStore PART 9).
   */
  public static String userSubHstId(final String targetAppId,
                                    final String actId,
                                    final String usrId,
                                    final String prvId)
  {
    return UserHostIds.resolve(targetAppId, actId, usrId, prvId);
  }

  /**
   * Dispatches InstallUser to {@code targetAppId}'s handler ({@code install}).
   *
   * @throws DomatarException if the response is Failure or transport fails
   */
  public static void sendInstallUser(String targetAppId,
                                       String actId,
                                       String usrId,
                                       String usrName,
                                       String prvId,
                                       String domain,
                                       DomatarMsgClient msgClient) throws DomatarException
  {
    final String appSubHstId = userSubHstId(targetAppId, actId, usrId, prvId);
    final String localPrvId  = DomatarConfig.getPrvId();
    final boolean local      = prvId != null && prvId.equals(localPrvId);

    if (local)
    {
      if (HstDb.getHst(appSubHstId) == null)
        HstDb.addHst(appSubHstId, domain, prvId);

      DirectoryRegister.registerHst(appSubHstId, domain, prvId, msgClient);
    }
    else
    {
      // Offering provider owns the host row; home only publishes directory.
      DirectoryRegister.publishHst(appSubHstId, domain, prvId, msgClient);
    }

    JsonMsg req = new JsonMsg();

    ObjAttrs attrs = new ObjAttrs();
    attrs.addAttr("ActId", actId);
    attrs.addAttr("UsrId", usrId);
    attrs.addAttr("UsrName", usrName);
    attrs.addAttr("PrvId", prvId);
    attrs.addAttr("Domain", domain);

    req.addRequestBody("InstallUser", attrs);
    req.addClsId(targetAppId, "install");

    DomId dst = new DomId(appSubHstId, targetAppId, actId, USER_INSTALL_OBJ_ID);

    JsonMsg resp = msgClient.send(dst, req);

    if (resp.isFailure())
    {
      String detail = resp.getErrorMsg();

      throw new DomatarException(
          detail != null && !detail.isEmpty()
              ? detail
              : "InstallUser failed for app " + targetAppId);
    }
  }
}
