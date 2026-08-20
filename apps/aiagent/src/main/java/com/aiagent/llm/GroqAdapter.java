/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.DomatarException;

/**
 * OpenAI-compatible adapter for the Groq cloud inference API.
 * Spec-AIAgent.txt PART 9.3.
 *
 * One instance is held by LlmClient for the lifetime of the JVM.
 * Thread-safe: java.net.http.HttpClient is thread-safe by contract.
 *
 * Supports both plain chat-completion and tool-calling (function-calling)
 * via the OpenAI tool_calls / role="tool" wire format.
 */
public class GroqAdapter
{
  private static final int    TIMEOUT_SECONDS  = 60;
  private static final int    MAX_TOKENS       = 1024;
  private static final double TEMPERATURE      = 0.7;
  private static final int    MAX_RATE_RETRIES = 3;
  private static final long   RATE_RETRY_MS    = 2000L;

  private final String     apiUrl;
  private final String     apiKey;
  private final HttpClient http;

  public GroqAdapter(final String apiUrl, final String apiKey)
  {
    this.apiUrl = apiUrl;
    this.apiKey = apiKey;
    this.http   = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();
  }

  /** Plain chat-completion; delegates to the tool-capable overload. */
  public LlmResult complete(final String model, final List<LlmMessage> messages)
      throws DomatarException
  {
    return complete(model, messages, null, null);
  }

  /**
   * Tool-capable completion.
   *
   * @param tools      tool catalogue; null or empty omits the tools field
   * @param toolChoice "auto", "none", or specific tool name; null -> "auto"
   *                   when tools are non-empty
   */
  public LlmResult complete(final String           model,
                             final List<LlmMessage> messages,
                             final List<LlmTool>    tools,
                             final String           toolChoice)
      throws DomatarException
  {
    final String      requestBody = buildRequestBody(model, messages, tools, toolChoice);
    final HttpRequest request     = HttpRequest.newBuilder()
        .uri(URI.create(apiUrl))
        .header("Content-Type",  "application/json")
        .header("Authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build();

    int retries = 0;

    while (true)
    {
      HttpResponse<String> response;

      try
      {
        response = http.send(request, HttpResponse.BodyHandlers.ofString());
      }
      catch (InterruptedException e)
      {
        Thread.currentThread().interrupt();
        throw new DomatarException("LLM request interrupted", e);
      }
      catch (Exception e)
      {
        throw new DomatarException("LLM request I/O error: " + e.getMessage(), e);
      }

      final int    status = response.statusCode();
      final String body   = response.body();

      if (status == 429)
      {
        if (retries >= MAX_RATE_RETRIES)
          throw new DomatarException(
              "LLM rate limit exceeded. Please try again later. " + truncate(body, 400));

        final long waitMs = parseRetryAfterMs(body);

        try
        {
          Thread.sleep(waitMs);
        }
        catch (InterruptedException ie)
        {
          Thread.currentThread().interrupt();
        }

        retries++;
        continue;
      }

      if (status == 401 || status == 403)
        throw new DomatarException(
            "LLM authentication failed (HTTP " + status + "). Check AIAGENT_API_KEY.");

      if (status != 200)
        throw new DomatarException(
            "LLM request failed: HTTP " + status + " — " + truncate(body, 300));

      return parseResponse(body);
    }
  }

  /**
   * Live Groq model ids that can take local function-calling tools.
   * Audio, guard, and compound (built-in tools only) ids are omitted.
   */
  public List<String> listToolModels() throws DomatarException
  {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(modelsUrl(apiUrl)))
        .header("Authorization", "Bearer " + apiKey)
        .GET()
        .timeout(Duration.ofSeconds(20))
        .build();

    HttpResponse<String> response;

    try
    {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    }
    catch (InterruptedException e)
    {
      Thread.currentThread().interrupt();
      throw new DomatarException("LLM models request interrupted", e);
    }
    catch (Exception e)
    {
      throw new DomatarException("LLM models request I/O error: " + e.getMessage(), e);
    }

    final int    status = response.statusCode();
    final String body   = response.body();

    if (status == 401 || status == 403)
      throw new DomatarException(
          "LLM authentication failed (HTTP " + status + "). Check AIAGENT_API_KEY.");

    if (status != 200)
      throw new DomatarException(
          "LLM models request failed: HTTP " + status + " — " + truncate(body, 300));

    return parseToolModels(body);
  }

  static String modelsUrl(final String chatCompletionsUrl)
  {
    if (chatCompletionsUrl == null || chatCompletionsUrl.isEmpty())
      return "https://api.groq.com/openai/v1/models";

    if (chatCompletionsUrl.endsWith("/chat/completions"))
      return chatCompletionsUrl.substring(0,
          chatCompletionsUrl.length() - "/chat/completions".length()) + "/models";

    if (chatCompletionsUrl.endsWith("/"))
      return chatCompletionsUrl + "models";

    return chatCompletionsUrl + "/models";
  }

  static List<String> parseToolModels(final String json) throws DomatarException
  {
    final JsonMap  root = Json.parseMap(json);
    final JsonList data = root.getList("data");
    final List<String> ids = new ArrayList<>();

    if (data == null)
      return ids;

    for (final Object o : data)
    {
      if (!(o instanceof JsonMap))
        continue;

      final JsonMap  entry  = (JsonMap) o;
      final String   id     = entry.getString("id");
      final Object   rawActive = entry.get("active");
      final Boolean  active = (rawActive instanceof Boolean)
          ? (Boolean) rawActive : null;
      final JsonList features = entry.getList("supported_features");

      if (isToolCapableChatModel(id, active, features))
        ids.add(id);
    }

    Collections.sort(ids);
    return ids;
  }

  /**
   * Groq marks local function calling with supported_features containing
   * "tools". Chat-looking models such as allam-2-7b only advertise json_mode.
   * Compound systems are built-in-tools agents, not local tool calling.
   */
  static boolean isToolCapableChatModel(final String   id,
                                        final Boolean  active,
                                        final JsonList features)
  {
    if (id == null || id.isEmpty())
      return false;

    if (Boolean.FALSE.equals(active))
      return false;

    if (id.toLowerCase(Locale.ROOT).contains("compound"))
      return false;

    return hasFeature(features, "tools");
  }

  static boolean hasFeature(final JsonList features, final String name)
  {
    if (features == null || name == null)
      return false;

    for (final Object o : features)
    {
      if (name.equals(o))
        return true;
    }

    return false;
  }

  // ── private helpers ──────────────────────────────────────────────────────

  private String buildRequestBody(final String           model,
                                   final List<LlmMessage> messages,
                                   final List<LlmTool>    tools,
                                   final String           toolChoice)
      throws DomatarException
  {
    final JsonList msgArray = new JsonArrayList(messages.size());

    for (final LlmMessage m : messages)
    {
      final JsonMap entry = new JsonHashMap(4);
      entry.put("role", m.role);

      if ("tool".equals(m.role))
      {
        // Role="tool" carries the tool result back to the LLM.
        entry.put("tool_call_id", m.toolCallId != null ? m.toolCallId : "");
        entry.put("content",      m.content    != null ? m.content    : "");
      }
      else if ("assistant".equals(m.role)
               && m.toolCalls != null
               && !m.toolCalls.isEmpty())
      {
        // Assistant turn that contained tool_calls (no text content required).
        entry.put("content", m.content != null ? m.content : "");

        final JsonList tcArray = new JsonArrayList(m.toolCalls.size());

        for (final LlmToolCall c : m.toolCalls)
        {
          final JsonMap fn = new JsonHashMap(2);
          fn.put("name",      c.name);
          fn.put("arguments", c.argumentsJson != null ? c.argumentsJson : "{}");

          final JsonMap tcEntry = new JsonHashMap(3);
          tcEntry.put("id",       c.id);
          tcEntry.put("type",     "function");
          tcEntry.put("function", fn);

          tcArray.add(tcEntry);
        }

        entry.put("tool_calls", tcArray);
      }
      else
      {
        // system / user / plain assistant (no tool_calls)
        entry.put("content", m.content != null ? m.content : "");
      }

      msgArray.add(entry);
    }

    final JsonMap body = new JsonHashMap(6);
    body.put("model",       model);
    body.put("messages",    msgArray);
    body.put("max_tokens",  MAX_TOKENS);
    body.put("temperature", (tools != null && !tools.isEmpty()) ? 0.2 : TEMPERATURE);

    if (tools != null && !tools.isEmpty())
    {
      final JsonList toolsArray = new JsonArrayList(tools.size());

      for (final LlmTool t : tools)
      {
        final JsonMap fn = new JsonHashMap(3);
        fn.put("name",        t.name);
        fn.put("description", t.description);
        // parametersSchema is a JSON Schema string; parse it back to a
        // JsonMap so the wire payload contains a real JSON object.
        fn.put("parameters",  Json.parseMap(t.parametersSchema));

        final JsonMap toolEntry = new JsonHashMap(2);
        toolEntry.put("type",     "function");
        toolEntry.put("function", fn);

        toolsArray.add(toolEntry);
      }

      body.put("tools",       toolsArray);
      body.put("tool_choice", toolChoice != null ? toolChoice : "auto");
    }

    return Json.toJson(body);
  }

  private LlmResult parseResponse(final String json)
      throws DomatarException
  {
    final JsonMap root    = Json.parseMap(json);
    final JsonList choices = root.getList("choices");

    if (choices == null || choices.isEmpty())
      throw new DomatarException("LLM response contained no choices");

    final JsonMap choice  = (JsonMap) choices.get(0);
    final JsonMap message = choice.getMap("message");

    String text         = message != null ? message.getString("content") : null;
    String finishReason = choice.getString("finish_reason");

    if (finishReason == null)
      finishReason = "stop";

    // Extract tool_calls from the assistant message when present.
    List<LlmToolCall> toolCalls = Collections.emptyList();

    if (message != null)
    {
      final JsonList rawToolCalls = message.getList("tool_calls");

      if (rawToolCalls != null && !rawToolCalls.isEmpty())
      {
        toolCalls = new ArrayList<>(rawToolCalls.size());

        for (final Object o : rawToolCalls)
        {
          if (!(o instanceof JsonMap))
            continue;

          final JsonMap tc = (JsonMap) o;
          final String  id = tc.getString("id");

          final JsonMap fn = tc.getMap("function");
          if (fn == null)
            continue;

          final String name = fn.getString("name");
          String argsJson   = fn.getString("arguments");
          if (argsJson == null)
            argsJson = "{}";

          toolCalls.add(new LlmToolCall(id, name, argsJson));
        }
      }
    }

    // An assistant turn with tool_calls carries empty text; normalise null.
    if (text == null)
      text = "";

    int tokensIn  = 0;
    int tokensOut = 0;

    final JsonMap usage = root.getMap("usage");

    if (usage != null)
    {
      final Number in  = usage.getNumber("prompt_tokens");
      final Number out = usage.getNumber("completion_tokens");
      if (in  != null)
        tokensIn  = in.intValue();
      if (out != null)
        tokensOut = out.intValue();
    }

    return new LlmResult(text, toolCalls, tokensIn, tokensOut, finishReason);
  }

  /**
   * Parse "Please try again in 1.845s" from a Groq 429 error body.
   * Returns the wait time in milliseconds (with a 500 ms buffer), or
   * RATE_RETRY_MS if the pattern is not found.
   */
  private static long parseRetryAfterMs(final String body)
  {
    final java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("try again in (\\d+\\.?\\d*)s")
        .matcher(body != null ? body : "");
    if (m.find())
    {
      try
      {
        final double seconds = Double.parseDouble(m.group(1));
        return (long)(seconds * 1000) + 500;
      }
      catch (NumberFormatException ignored)
      {
      }
    }
    return RATE_RETRY_MS;
  }

  private static String truncate(final String s, final int max)
  {
    if (s == null || s.length() <= max)
      return s;
    return s.substring(0, max) + "...";
  }
}
