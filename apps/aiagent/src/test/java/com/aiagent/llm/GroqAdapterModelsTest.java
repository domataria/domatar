/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonList;
import com.domatar.util.DomatarException;

public class GroqAdapterModelsTest
{
  @Test
  public void modelsUrlRewritesChatCompletions()
  {
    assertEquals("https://api.groq.com/openai/v1/models",
        GroqAdapter.modelsUrl(
            "https://api.groq.com/openai/v1/chat/completions"));
  }

  @Test
  public void modelsUrlEmptyFallsBackToGroq()
  {
    assertEquals("https://api.groq.com/openai/v1/models",
        GroqAdapter.modelsUrl(""));
  }

  @Test
  public void toolFilterRequiresToolsFeature()
  {
    assertTrue(GroqAdapter.isToolCapableChatModel(
        "openai/gpt-oss-120b", Boolean.TRUE, features("tools", "json_mode")));
    assertTrue(GroqAdapter.isToolCapableChatModel(
        "qwen/qwen3.6-27b", null, features("tools")));
    assertFalse(GroqAdapter.isToolCapableChatModel(
        "allam-2-7b", Boolean.TRUE, features("json_mode")));
    assertFalse(GroqAdapter.isToolCapableChatModel(
        "openai/gpt-oss-120b", Boolean.TRUE, features("json_mode")));
  }

  @Test
  public void toolFilterDropsCompoundAndInactiveEvenWithTools()
  {
    assertFalse(GroqAdapter.isToolCapableChatModel(
        "groq/compound", Boolean.TRUE, features("tools")));
    assertFalse(GroqAdapter.isToolCapableChatModel(
        "openai/gpt-oss-20b", Boolean.FALSE, features("tools")));
  }

  @Test
  public void parseToolModelsKeepsOnlyToolsFeature() throws DomatarException
  {
    final String json = "{"
        + "\"data\":["
        +   "{\"id\":\"allam-2-7b\",\"active\":true,"
        +     "\"supported_features\":[\"json_mode\"]},"
        +   "{\"id\":\"openai/gpt-oss-120b\",\"active\":true,"
        +     "\"supported_features\":[\"tools\",\"json_mode\"]},"
        +   "{\"id\":\"groq/compound-mini\",\"active\":true,"
        +     "\"supported_features\":[\"json_mode\"]},"
        +   "{\"id\":\"qwen/qwen3.6-27b\",\"active\":true,"
        +     "\"supported_features\":[\"tools\"]},"
        +   "{\"id\":\"whisper-large-v3\",\"active\":true},"
        +   "{\"id\":\"retired-tools\",\"active\":false,"
        +     "\"supported_features\":[\"tools\"]}"
        + "]}";

    final List<String> ids = GroqAdapter.parseToolModels(json);

    assertEquals(2, ids.size());
    assertEquals("openai/gpt-oss-120b", ids.get(0));
    assertEquals("qwen/qwen3.6-27b", ids.get(1));
  }

  private static JsonList features(final String... names)
  {
    final JsonList list = new JsonArrayList(names.length);
    for (final String name : names)
      list.add(name);
    return list;
  }
}
