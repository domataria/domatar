/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.llm;

import java.util.List;

import com.domatar.util.DomatarException;

/**
 * Entry point for all LLM calls within the AI Agent app.
 * Spec-AIAgent.txt PART 9.4.
 *
 * Reads four environment variables once at class-init time:
 *   AIAGENT_PROVIDER  — default "groq"
 *   AIAGENT_API_URL   — default Groq chat-completions endpoint
 *   AIAGENT_MODEL     — default "llama-3.3-70b-versatile"
 *   AIAGENT_API_KEY   — no default; throws on the first complete() call if absent
 *
 * Adding a new provider later: add its adapter class and a case in the
 * four-arg complete() switch below.
 */
public class LlmClient
{
  private static final String DEFAULT_PROVIDER = "groq";
  private static final String DEFAULT_API_URL  =
      "https://api.groq.com/openai/v1/chat/completions";
  private static final String DEFAULT_MODEL    = "llama-3.3-70b-versatile";

  private static final String provider;
  private static final String apiKey;

  /** Exposed so ConvImpl can fall back to this when no model is stored on the conv obj. */
  public  static final String defaultModel;

  private static final GroqAdapter groqAdapter;

  static
  {
    provider     = envOr("AIAGENT_PROVIDER", DEFAULT_PROVIDER);
    final String url = envOr("AIAGENT_API_URL",  DEFAULT_API_URL);
    apiKey       = System.getenv("AIAGENT_API_KEY");   // may be null
    defaultModel = envOr("AIAGENT_MODEL",    DEFAULT_MODEL);

    // Adapter is created now so any construction-time failures surface at startup.
    // The empty-string fallback for apiKey means the adapter object is valid;
    // the null check in complete() rejects calls before they hit the wire.
    groqAdapter = new GroqAdapter(url, apiKey != null ? apiKey : "");
  }

  /**
   * Plain chat-completion request (no tools). Delegates to the full overload.
   *
   * @param model    model string; null/empty uses defaultModel
   * @param messages ordered history including system prompt and prior turns
   */
  public static LlmResult complete(final String model, final List<LlmMessage> messages)
      throws DomatarException
  {
    return complete(model, messages, null, null);
  }

  /**
   * Tool-capable completion request.
   *
   * @param model      model string; null/empty uses defaultModel
   * @param messages   ordered history
   * @param tools      tool catalogue to include; null or empty omits the tools field
   * @param toolChoice "auto", "none", or a specific tool name; null defaults to "auto"
   *                   when tools are present
   */
  public static LlmResult complete(String                 model,
                                    final List<LlmMessage> messages,
                                    final List<LlmTool>    tools,
                                    final String           toolChoice)
      throws DomatarException
  {
    if (apiKey == null || apiKey.isEmpty())
      throw new DomatarException(
          "AI Agent not configured: AIAGENT_API_KEY environment variable is missing.");

    if (model == null || model.isEmpty())
      model = defaultModel;

    switch (provider.toLowerCase())
    {
      case "groq":
        return groqAdapter.complete(model, messages, tools, toolChoice);

      default:
        throw new DomatarException(
            "Unknown AIAGENT_PROVIDER '" + provider + "'. Supported values: groq");
    }
  }

  /**
   * Tool-capable chat model ids from the configured provider.
   * Used by the AI Agent model drop-down.
   */
  public static List<String> listToolModels() throws DomatarException
  {
    if (apiKey == null || apiKey.isEmpty())
      throw new DomatarException(
          "AI Agent not configured: AIAGENT_API_KEY environment variable is missing.");

    switch (provider.toLowerCase())
    {
      case "groq":
        return groqAdapter.listToolModels();

      default:
        throw new DomatarException(
            "Unknown AIAGENT_PROVIDER '" + provider + "'. Supported values: groq");
    }
  }

  private static String envOr(final String name, final String defaultValue)
  {
    final String v = System.getenv(name);
    return (v != null && !v.isEmpty()) ? v : defaultValue;
  }

  private LlmClient()
  {
  }
}
