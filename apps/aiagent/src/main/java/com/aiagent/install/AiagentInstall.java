/*
 * Copyright (c) 2024 Domatar
 */

package com.aiagent.install;

import com.domatar.db.HstDb;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.install.AppInstall;
import com.domatar.install.CatalogInstall;
import com.domatar.install.ClsInstall;
import com.domatar.install.SrvInstall;
import com.domatar.util.Lnk;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Install routine for the AI Agent app.
 * Spec-AIAgent.txt PART 11.
 *
 * Creates the per-user AI Agent skeleton on the user's home prv:
 *   - aiagent~<actId>  hst row
 *   - conversations    container obj on aiagent~<actId>
 *   - app-aiagent      obj on navigator~<actId>  (seqNum 5)
 *   - root -> app-aiagent          lnk
 *   - app-aiagent -> conversations lnk
 *   - app-aiagent -> clss          lnk  (Classes container)
 *   - clss -> conversations/conv/msg class descriptors
 *
 * NavigatorInstall MUST have run before this so that navigator~<actId>
 * and the root obj already exist.
 *
 * Every step is idempotent: a second call is a no-op.
 */
public class AiagentInstall implements AppInstall
{
  @Override
  public void installProvider(final String prvId, final String domain) throws DomatarException
  {
    CatalogInstall.registerInCatalog(prvId, "aiagent");
  }

  @Override
  public void installUser(final String           actId,
                          final String           usrId,
                          final String           usrName,
                          final String           prvId,
                          final String           domain,
                          final DomatarMsgClient msgClient) throws DomatarException
  {
    install(actId, usrName, domain, prvId);
  }

  private static void install(final String actId,
                               final String usrName,
                               final String domain,
                               final String prvId) throws DomatarException
  {
    final String aiHstId  = DomId.subHstId("aiagent",   actId);
    final String navHstId = DomId.subHstId("navigator", actId, prvId);

    // 1. Per-user aiagent sub-host
    if (HstDb.getHst(aiHstId) == null)
      HstDb.addHst(aiHstId, domain, prvId);

    final DomId rootId  = new DomId(navHstId, "navigator", actId, "root");
    final DomId appAiId = new DomId(aiHstId,  "aiagent",   actId, "app-aiagent");
    final DomId convsId = new DomId(aiHstId,  "aiagent",   actId, "conversations");

    // 2. conversations container obj on aiagent~<actId>
    ObjDb.addObjIfMissing(convsId, "aiagent", "conversations",
                          "Conversations", "AI chat conversations");

    // 3. app-aiagent obj on aiagent~<actId>  (seqNum 5: appears after Navigator)
    ObjDb.addObjIfMissing(appAiId, "aiagent", "app",
                          "AI Agent", "Chat with an AI assistant");
    ObjDb.reclassObj(appAiId, "aiagent", "app");

    // 4. root -> app-aiagent lnk
    addLnkIfMissing(rootId, appAiId,
                    "aiagent", "app",
                    "AI Agent", "Chat with an AI assistant",
                    "navigator", "app", null, 5);

    // 5. app-aiagent -> conversations lnk
    addLnkIfMissing(appAiId, convsId,
                    "aiagent", "conversations",
                    "Conversations", "AI chat conversations",
                    "navigator", "container", null, 1);

    // 6. Services container + class container + service/slim-class descriptor objects
    //    (Spec-Service.txt, Spec-AIAgent.txt PART 16.2).
    SrvInstall.ensureSrvsContainer(appAiId,
        "Service descriptors for AI Agent", "aiagent", 2);
    ClsInstall.ensureClssContainer(appAiId,
        "Class descriptors for AI Agent", "aiagent", 3);

    // aiagent.app — structured LLM-native entry point with natural-language descriptions
    SrvInstall.upsertSrvObj(appAiId, "aiagent", "app",
        "AI Agent app entry point (LLM-native operations)",
        "{" +
        "\"Description\":\"AI Agent app entry point. Use these LLM-native operations to query the user's conversation history. Call GetConversations first to discover titles, then GetConversation to read a specific thread.\"," +
        "\"Msgs\":[" +
        "{\"Name\":\"GetConversations\",\"Description\":\"List all AI chat conversations for the current user, with title, mode, message count, and last-updated time.\",\"SideEffect\":\"Read\"}," +
        "{\"Name\":\"GetConversation\",\"Description\":\"Get the full message history of a specific conversation by its title.\",\"SideEffect\":\"Read\"," +
        "\"Parms\":[{\"Name\":\"Title\",\"Type\":\"String\",\"Description\":\"The title of the conversation to retrieve.\"}]}" +
        "]," +
        "\"Attrs\":\"DisplayName, IconPath, LaunchPath\"" +
        "}");

    ClsInstall.upsertClsImplementing(appAiId, "aiagent", "app",
        "AI Agent app entry point (LLM-native operations)",
        "{\"ClsAppId\":\"aiagent\",\"ClsId\":\"app\"," +
        "\"Implements\":[\"aiagent.app\"]," +
        "\"Auth\":\"isVerified\"" +
        "}");

    // aiagent.conversations, aiagent.conv, aiagent.msg — structured with SideEffect/Auth in Msgs
    SrvInstall.addSrvObj(convsId, "aiagent", "conversations",
        "Container of AI chat conversations for one account",
        conversationsSrvJson());

    ClsInstall.upsertClsImplementing(convsId, "aiagent", "conversations",
        "Container of AI chat conversations for one account",
        conversationsClsJson());

    SrvInstall.addSrvObj(convsId, "aiagent", "conv",
        "A single AI conversation thread with message history",
        convSrvJson());

    ClsInstall.upsertClsImplementing(convsId, "aiagent", "conv",
        "A single AI conversation thread with message history",
        convClsJson());

    SrvInstall.addSrvObj(convsId, "aiagent", "msg",
        "A single message row within an AI conversation",
        msgSrvJson());

    ClsInstall.upsertClsImplementing(convsId, "aiagent", "msg",
        "A single message row within an AI conversation",
        msgClsJson());
  }

  // ── service descriptor JSON builders (interface only — no SideEffect/Auth) ─

  private static String conversationsSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"aiagent\","
        + "\"SrvId\":\"conversations\","
        + "\"Description\":\"The per-user container that holds every AI"
        +   " conversation thread the user owns. Singleton; ObjId is always"
        +   " 'conversations'. New conversations are added under it via the"
        +   " SendMessage operation when ConvId is empty, and listed via"
        +   " ListConversations.\","
        + "\"Attrs\":[],"
        + "\"Msgs\":["
        +   "{"
        +     "\"Name\":\"ListConversations\","
        +     "\"Description\":\"Return all conversations owned by the calling user,"
        +       " sorted by UpdatedAt descending (newest activity first).\","
        +     "\"Type\":{\"Conversations\":["
        +       "{\"ConvId\":\"String\",\"Title\":\"String\","
        +        "\"CreatedAt\":\"String\",\"UpdatedAt\":\"String\","
        +        "\"MessageCount\":\"String\",\"Model\":\"String\","
        +        "\"Mode\":\"String\"}"
        +     "]},"
        +     "\"Parms\":[]"
        +   "},"
        +   "{"
        +     "\"Name\":\"SendMessage\","
        +     "\"Description\":\"Create a new conversation seeded with the given Text"
        +       " as the first user message. Returns the new ConvId plus the resulting"
        +       " user/assistant turns. Use this when the user starts a new chat thread;"
        +       " for an existing thread, send to (aiagent, conv) instead.\","
        +     "\"Type\":{\"ConvId\":\"String\",\"UserMessage\":{},\"AssistantMessage\":{}},"
        +     "\"Parms\":["
        +       "{\"Name\":\"Text\",\"Type\":\"String\","
        +        "\"Description\":\"The user's first message text.\"},"
        +       "{\"Name\":\"Model\",\"Type\":\"String\","
        +        "\"Description\":\"Tool-capable LLM model id from ListModels.\"},"
        +       "{\"Name\":\"Mode?\",\"Type\":\"String\","
        +        "\"Description\":\"Chat, ReadOnly, or Agent.\"}"
        +     "]"
        +   "}"
        + "]}";
  }

  private static String convSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"aiagent\","
        + "\"SrvId\":\"conv\","
        + "\"Description\":\"A single AI conversation thread: a sequence of user,"
        +   " assistant, and (in agent mode) tool messages, plus the model and policy"
        +   " that govern it.\","
        + "\"Attrs\":["
        +   "{\"Name\":\"Title\",\"Type\":\"String\","
        +    "\"Description\":\"The user-visible title; defaults to the first 40 chars"
        +      " of the seed message; user-renameable via RenameConversation.\"},"
        +   "{\"Name\":\"Model\",\"Type\":\"String\","
        +    "\"Description\":\"The LLM model id used for completions in this conversation.\"},"
        +   "{\"Name\":\"SystemPrompt\",\"Type\":\"String\","
        +    "\"Description\":\"The system-role instruction sent before the user/assistant history.\"},"
        +   "{\"Name\":\"CreatedAt\",\"Type\":\"String\","
        +    "\"Description\":\"Unix-ms when the conversation was created.\"},"
        +   "{\"Name\":\"UpdatedAt\",\"Type\":\"String\","
        +    "\"Description\":\"Unix-ms of the most recent persisted message.\"},"
        +   "{\"Name\":\"MessageCount\",\"Type\":\"String\","
        +    "\"Description\":\"Total number of msg rows linked under this conv.\"},"
        +   "{\"Name\":\"TokensIn\",\"Type\":\"String\","
        +    "\"Description\":\"Running prompt-token total across all turns.\"},"
        +   "{\"Name\":\"TokensOut\",\"Type\":\"String\","
        +    "\"Description\":\"Running completion-token total across all turns.\"},"
        +   "{\"Name\":\"Mode\",\"Type\":\"String\","
        +    "\"Description\":\"One of 'Chat' / 'ReadOnly' / 'Agent'.\"},"
        +   "{\"Name\":\"Policy\",\"Type\":\"String\","
        +    "\"Description\":\"Stringified JSON Policy document.\"},"
        +   "{\"Name\":\"ForeignActIds\",\"Type\":\"String\","
        +    "\"Description\":\"Stringified JSON array of foreign actIds.\"}"
        + "],"
        + "\"Msgs\":["
        +   "{"
        +     "\"Name\":\"GetConversation\","
        +     "\"Description\":\"Return the conversation's metadata and full message list.\","
        +     "\"Type\":{\"ConvId\":\"String\",\"Title\":\"String\","
        +               "\"Model\":\"String\",\"Mode\":\"String\","
        +               "\"Messages\":[{\"Role\":\"String\",\"Text\":\"String\","
        +                              "\"Time\":\"String\"}]},"
        +     "\"Parms\":[]"
        +   "},"
        +   "{"
        +     "\"Name\":\"SendMessage\","
        +     "\"Description\":\"Append a user message to this conversation and return all turns.\","
        +     "\"Type\":{\"ConvId\":\"String\",\"UserMessage\":{},\"AssistantMessage\":{}},"
        +     "\"Parms\":["
        +       "{\"Name\":\"Text\",\"Type\":\"String\","
        +        "\"Description\":\"The user's message text.\"},"
        +       "{\"Name\":\"Model\",\"Type\":\"String\","
        +        "\"Description\":\"Tool-capable LLM model id from ListModels.\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"RenameConversation\","
        +     "\"Description\":\"Set a new Title for the conversation.\","
        +     "\"Type\":{\"Renamed\":\"String\"},"
        +     "\"Parms\":["
        +       "{\"Name\":\"Title\",\"Type\":\"String\","
        +        "\"Description\":\"The new title.\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"DeleteConversation\","
        +     "\"Description\":\"Delete this conversation and every msg row linked under it.\","
        +     "\"Type\":{\"Deleted\":\"String\",\"ConvId\":\"String\"},"
        +     "\"Parms\":[]"
        +   "},"
        +   "{"
        +     "\"Name\":\"SetMode\","
        +     "\"Description\":\"Change the conversation's Mode.\","
        +     "\"Type\":{\"Mode\":\"String\"},"
        +     "\"Parms\":["
        +       "{\"Name\":\"Mode\",\"Type\":\"String\","
        +        "\"Description\":\"The new Mode: Chat, ReadOnly, or Agent.\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"SetPolicy\","
        +     "\"Description\":\"Replace the conversation's Policy document.\","
        +     "\"Type\":{\"Saved\":\"String\"},"
        +     "\"Parms\":["
        +       "{\"Name\":\"Policy\",\"Type\":\"String\","
        +        "\"Description\":\"Stringified JSON Policy document.\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"SetForeignActIds\","
        +     "\"Description\":\"Replace the conversation's ForeignActIds list.\","
        +     "\"Type\":{\"Saved\":\"String\"},"
        +     "\"Parms\":["
        +       "{\"Name\":\"ForeignActIds\",\"Type\":\"String\","
        +        "\"Description\":\"Stringified JSON array of actId strings.\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"ApproveToolCall\","
        +     "\"Description\":\"Approve a pending tool-call proposal and resume the agent loop.\","
        +     "\"Type\":{\"Resumed\":\"String\"},"
        +     "\"Parms\":["
        +       "{\"Name\":\"PendingId\",\"Type\":\"String\","
        +        "\"Description\":\"The id of the pending tool call to approve.\"},"
        +       "{\"Name\":\"Always?\",\"Type\":\"String\","
        +        "\"Description\":\"Set 'True' to add this tool to PreApprovedWrites.\"}"
        +     "]"
        +   "},"
        +   "{"
        +     "\"Name\":\"RejectToolCall\","
        +     "\"Description\":\"Reject a pending tool-call proposal and resume the agent loop.\","
        +     "\"Type\":{\"Resumed\":\"String\"},"
        +     "\"Parms\":["
        +       "{\"Name\":\"PendingId\",\"Type\":\"String\","
        +        "\"Description\":\"The id of the pending tool call to reject.\"}"
        +     "]"
        +   "}"
        + "]}";
  }

  private static String msgSrvJson()
  {
    return "{"
        + "\"SrvAppId\":\"aiagent\","
        + "\"SrvId\":\"msg\","
        + "\"Description\":\"A single message row inside an AI conversation.\","
        + "\"Attrs\":["
        +   "{\"Name\":\"ConvId\",\"Type\":\"String\","
        +    "\"Description\":\"ObjId of the parent conv row.\"},"
        +   "{\"Name\":\"Role\",\"Type\":\"String\","
        +    "\"Description\":\"One of 'user', 'assistant', or 'tool'.\"},"
        +   "{\"Name\":\"Text\",\"Type\":\"String\","
        +    "\"Description\":\"The message body.\"},"
        +   "{\"Name\":\"Time\",\"Type\":\"String\","
        +    "\"Description\":\"Unix-ms timestamp when this row was written.\"},"
        +   "{\"Name\":\"Model?\",\"Type\":\"String\"},"
        +   "{\"Name\":\"TokensIn?\",\"Type\":\"String\"},"
        +   "{\"Name\":\"TokensOut?\",\"Type\":\"String\"},"
        +   "{\"Name\":\"FinishReason?\",\"Type\":\"String\"},"
        +   "{\"Name\":\"ToolName?\",\"Type\":\"String\"},"
        +   "{\"Name\":\"ToolTargetSov?\",\"Type\":\"String\"},"
        +   "{\"Name\":\"ToolArgs?\",\"Type\":\"String\"},"
        +   "{\"Name\":\"ToolResult?\",\"Type\":\"String\"},"
        +   "{\"Name\":\"ToolStatus?\",\"Type\":\"String\"},"
        +   "{\"Name\":\"PendingId?\",\"Type\":\"String\"}"
        + "],"
        + "\"Msgs\":["
        +   "{\"Name\":\"GetObj\","
        +    "\"Description\":\"Return the ObjName and ObjDesc of this message row.\","
        +    "\"Type\":{\"ObjName\":\"String\",\"ObjDesc\":\"String\"},"
        +    "\"Parms\":[]}"
        + "]}";
  }

  // ── slim class descriptor JSON builders (Implements + MsgPolicy) ──────────

  private static String conversationsClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"aiagent\","
        + "\"ClsId\":\"conversations\","
        + "\"Implements\":[\"aiagent.conversations\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"aiagent.conversations\",\"Name\":\"ListConversations\",\"SideEffect\":\"Read\",\"Auth\":\"Owner\"},"
        +   "{\"Srv\":\"aiagent.conversations\",\"Name\":\"SendMessage\",\"SideEffect\":\"Write\",\"Auth\":\"Owner\"}"
        + "]}";
  }

  private static String convClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"aiagent\","
        + "\"ClsId\":\"conv\","
        + "\"Implements\":[\"aiagent.conv\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"aiagent.conv\",\"Name\":\"GetConversation\",\"SideEffect\":\"Read\",\"Auth\":\"Owner\"},"
        +   "{\"Srv\":\"aiagent.conv\",\"Name\":\"SendMessage\",\"SideEffect\":\"Write\",\"Auth\":\"Owner\"},"
        +   "{\"Srv\":\"aiagent.conv\",\"Name\":\"RenameConversation\",\"SideEffect\":\"Write\",\"Auth\":\"Owner\"},"
        +   "{\"Srv\":\"aiagent.conv\",\"Name\":\"DeleteConversation\",\"SideEffect\":\"Destructive\",\"Auth\":\"Owner\"},"
        +   "{\"Srv\":\"aiagent.conv\",\"Name\":\"SetMode\",\"SideEffect\":\"Write\",\"Auth\":\"Owner\"},"
        +   "{\"Srv\":\"aiagent.conv\",\"Name\":\"SetPolicy\",\"SideEffect\":\"Write\",\"Auth\":\"Owner\"},"
        +   "{\"Srv\":\"aiagent.conv\",\"Name\":\"SetForeignActIds\",\"SideEffect\":\"Write\",\"Auth\":\"Owner\"},"
        +   "{\"Srv\":\"aiagent.conv\",\"Name\":\"ApproveToolCall\",\"SideEffect\":\"Write\",\"Auth\":\"Owner\"},"
        +   "{\"Srv\":\"aiagent.conv\",\"Name\":\"RejectToolCall\",\"SideEffect\":\"Write\",\"Auth\":\"Owner\"}"
        + "]}";
  }

  private static String msgClsJson()
  {
    return "{"
        + "\"ClsAppId\":\"aiagent\","
        + "\"ClsId\":\"msg\","
        + "\"Implements\":[\"aiagent.msg\"],"
        + "\"MsgPolicy\":["
        +   "{\"Srv\":\"aiagent.msg\",\"Name\":\"GetObj\",\"SideEffect\":\"Read\",\"Auth\":\"Owner\"}"
        + "]}";
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static void addLnkIfMissing(final DomId   domId,
                                       final DomId   lnkDomId,
                                       final String  lnkClsAppId,
                                       final String  lnkClsId,
                                       final String  lnkObjName,
                                       final String  lnkObjDesc,
                                       final String  tagAppId,
                                       final String  tag,
                                       final String  val,
                                       final long    seqNum) throws DomatarException
  {
    if (LnkDb.getLnk(domId, lnkDomId, tagAppId, tag) == null)
      LnkDb.addLnk(new Lnk(domId, lnkDomId,
                            lnkClsAppId, lnkClsId,
                            lnkObjName, lnkObjDesc,
                            tagAppId, tag,
                            val, seqNum));
  }
}
