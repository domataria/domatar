/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.domatar.util.DomId;

/**
 * L6.1 matcher: bethb bookstore host on prv2 is portable; substrate /
 * shells / central catalogs are not.
 */
public class HostPresencePortableAppHostsTest
{
  private static final String BETHB = "jeRzqSwBdnX24hrCLmnAprOkv6GcJWaa";

  @Test
  public void bethbBookstoreOnPrv2_isPortable()
  {
    assertTrue(HostPresence.isPortableUserAppHost(
        "bookstore-" + BETHB, BETHB, "prv2"));
  }

  @Test
  public void substrateAndShellReplicas_areNotPortable()
  {
    assertFalse(HostPresence.isPortableUserAppHost(
        UserSubstrateIds.hstId(BETHB, "prv2"), BETHB, "prv2"));
    assertFalse(HostPresence.isPortableUserAppHost(
        DomId.subHstId("login", BETHB, "prv2"), BETHB, "prv2"));
    assertFalse(HostPresence.isPortableUserAppHost(
        DomId.subHstId("desktop", BETHB, "prv2"), BETHB, "prv2"));
    assertFalse(HostPresence.isPortableUserAppHost(
        DomId.subHstId("navigator", BETHB, "prv2"), BETHB, "prv2"));
    assertFalse(HostPresence.isPortableUserAppHost(
        DomId.subHstId("appstore", BETHB, "prv2"), BETHB, "prv2"));
  }

  @Test
  public void centralHostsWithoutActId_areNotPortable()
  {
    assertFalse(HostPresence.isPortableUserAppHost("bookstore", BETHB, "prv2"));
    assertFalse(HostPresence.isPortableUserAppHost("appstore", BETHB, "prv2"));
  }

  @Test
  public void hookForm_isPortable()
  {
    assertTrue(HostPresence.isPortableUserAppHost(
        "aiagent-" + BETHB + "-hook", BETHB, "prv2"));
  }
}
