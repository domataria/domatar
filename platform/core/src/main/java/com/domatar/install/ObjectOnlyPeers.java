/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import com.domatar.util.IdGen;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;

/**
 * Object-only membership peers ({@code peer-prv-&lt;prvId&gt;}).
 * Spec-Foreign-Provider-Installation.txt PART 8 / Update KD1.
 */
public final class ObjectOnlyPeers
{
  public static final String OBJ_ID_PREFIX = "peer-prv";

  private ObjectOnlyPeers() {}

  public static String objId(final String prvId)
  {
    return IdGen.createId(OBJ_ID_PREFIX, prvId);
  }

  public static boolean isObjectOnlyObjId(final String objId)
  {
    return objId != null && objId.startsWith(OBJ_ID_PREFIX + "-");
  }

  /**
   * Login-home when the attr is missing (legacy rows). False only when
   * explicitly {@code IsLoginHome=False}.
   */
  public static boolean isLoginHome(final ObjAttrs attrs) throws DomatarException
  {
    if (attrs == null)
      return true;

    return !"False".equals(attrs.getAttr("IsLoginHome"));
  }

  public static DomId rowDomId(final DomId membershipDst, final String prvId)
      throws DomatarException
  {
    return new DomId(membershipDst.hstId, "domatar", membershipDst.actId,
        objId(prvId));
  }
}
