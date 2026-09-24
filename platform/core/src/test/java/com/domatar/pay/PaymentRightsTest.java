package com.domatar.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.domatar.util.Rights;

public class PaymentRightsTest
{
  @Test
  public void hasRightsTrue_mapsToAdmit() throws Exception
  {
    final PaymentProbeImpl probe = new PaymentProbeImpl();
    probe.allow = true;
    probe.scripted = null;
    assertEquals(Rights.ADMIT, probe.rights(null, null, null));
  }

  @Test
  public void hasRightsFalse_mapsToDeny() throws Exception
  {
    final PaymentProbeImpl probe = new PaymentProbeImpl();
    probe.allow = false;
    probe.scripted = null;
    assertEquals(Rights.DENY, probe.rights(null, null, null));
  }

  @Test
  public void scriptedPriced_returnedAsIs() throws Exception
  {
    // Priced classes own rights(). They must still return true from
    // hasRights for callers they intend to serve, because handleMsg
    // keeps the boolean check.
    final PaymentProbeImpl probe = new PaymentProbeImpl();
    probe.allow = false;
    probe.scripted = Rights.PRICED;
    assertEquals(Rights.PRICED, probe.rights(null, null, null));
  }
}
