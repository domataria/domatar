/*
 * Copyright (c) 2024 Domatar
 */

package com.spreadsheet.formula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class AttrPathTest
{
  private static final String ALLOC =
      "[{\"Name\":\"OpenAI\",\"Weight\":\"0.28\"},"
      + "{\"Name\":\"SpaceX\",\"Weight\":\"0.22\"},"
      + "{\"Name\":\"Anthropic\",\"Weight\":\"0.18\"}]";

  @Test
  public void rootNameStopsAtIndexOrDot()
  {
    assertEquals("Allocations", AttrPath.rootName("Allocations[2].Name"));
    assertEquals("Signatories", AttrPath.rootName("Signatories[0]"));
    assertEquals("Nav", AttrPath.rootName("Nav"));
  }

  @Test
  public void walkIndexThenField()
  {
    assertEquals("Anthropic", AttrPath.walk(ALLOC, "[2].Name"));
    assertEquals("0.18", AttrPath.walk(ALLOC, "[2].Weight"));
  }

  @Test
  public void walkIndexReturnsElementJson()
  {
    final String el = AttrPath.walk(ALLOC, "[0]");
    assertTrue(el.contains("OpenAI"));
    assertTrue(el.contains("0.28"));
  }

  @Test
  public void emptySuffixIsRoot()
  {
    assertEquals(ALLOC, AttrPath.walk(ALLOC, ""));
    assertEquals(ALLOC, AttrPath.walk(ALLOC, null));
  }

  @Test
  public void missingIndexOrFieldIsNull()
  {
    assertNull(AttrPath.walk(ALLOC, "[9].Name"));
    assertNull(AttrPath.walk(ALLOC, "[2].Missing"));
    assertNull(AttrPath.walk(ALLOC, "[2][0]"));
    assertNull(AttrPath.walk("not-json", "[0]"));
  }

  @Test
  public void primitiveArrayIndex()
  {
    assertEquals("alice", AttrPath.walk("[\"alice\",\"bob\"]", "[0]"));
    assertEquals("bob", AttrPath.walk("[\"alice\",\"bob\"]", "[1]"));
  }
}
