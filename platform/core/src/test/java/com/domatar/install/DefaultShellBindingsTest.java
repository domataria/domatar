/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DefaultShellBindingsTest
{
  @Test
  void stock_mapsEachRoleToItself()
  {
    final Map<String, String> s = DefaultShellBindings.stock();
    assertEquals("login", s.get("login"));
    assertEquals("desktop", s.get("desktop"));
    assertEquals("navigator", s.get("navigator"));
    assertEquals("appstore", s.get("appstore"));
    assertEquals(4, s.size());
  }

  @Test
  void parse_and_format_roundTrip()
  {
    final String compact =
        "login=login,desktop=altDesktop,navigator=navigator,appstore=appstore";
    final Map<String, String> m = DefaultShellBindings.parse(compact);
    assertEquals("altDesktop", m.get("desktop"));
    assertEquals(compact, DefaultShellBindings.format(m));
  }

  @Test
  void normalize_fillsMissingRolesFromStock()
  {
    final Map<String, String> in = new LinkedHashMap<>();
    in.put("desktop", "altDesktop");
    final Map<String, String> out = DefaultShellBindings.normalize(in);
    assertEquals("login", out.get("login"));
    assertEquals("altDesktop", out.get("desktop"));
    assertEquals("navigator", out.get("navigator"));
    assertEquals("appstore", out.get("appstore"));
  }

  @Test
  void resolve_nullPrvActId_returnsStock() throws Exception
  {
    final Map<String, String> m = DefaultShellBindings.resolve(null);
    assertEquals(DefaultShellBindings.stock(), m);
  }

  @Test
  void parse_ignoresMalformedPairs()
  {
    final Map<String, String> m =
        DefaultShellBindings.parse("login=login,=bad,desktop,navigator=navigator");
    assertEquals("login", m.get("login"));
    assertEquals("navigator", m.get("navigator"));
    assertEquals(2, m.size());
  }
}
