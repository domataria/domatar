/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.domatar.util.ObjAttrs;
import com.domatar.util.DomatarException;

public class ObjectOnlyPeersTest
{
  @Test
  public void objId_prv2_isPeerPrvPrv2()
  {
    assertEquals("peer-prv-prv2", ObjectOnlyPeers.objId("prv2"));
  }

  @Test
  public void isObjectOnlyObjId_trueForPrefix()
  {
    assertTrue(ObjectOnlyPeers.isObjectOnlyObjId("peer-prv-prv2"));
    assertFalse(ObjectOnlyPeers.isObjectOnlyObjId("peer-bethb@quippin"));
    assertFalse(ObjectOnlyPeers.isObjectOnlyObjId(null));
  }

  @Test
  public void isLoginHome_missingAttr_true() throws DomatarException
  {
    assertTrue(ObjectOnlyPeers.isLoginHome(null));
    assertTrue(ObjectOnlyPeers.isLoginHome(new ObjAttrs()));
  }

  @Test
  public void isLoginHome_falseAttr_false() throws DomatarException
  {
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("IsLoginHome", "False");
    assertFalse(ObjectOnlyPeers.isLoginHome(attrs));
  }
}
