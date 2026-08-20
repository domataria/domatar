# SPEC: AI AGENT - the Domatar AI agent app

## PART 1 — VISION

The AI Agent is the Domatar app that lets a user delegate questions
and tasks to a Large Language Model with working access to the user's
Domatar object graph - and, with permission, to objects belonging to
other users on the network.

  +------------------+----------------------------------+
  |                  | David B.                         |
  |  Conversations   | -------------------------------- |
  |                  | > Why is the sky blue?           |
  |  + New           |                                  |
  |  - Sky thoughts  | Sunlight scatters in the         |
  |  - Recipe ideas  | atmosphere. Shorter (blue)       |
  |  - Project plan  | wavelengths scatter more...      |
  |                  |                                  |
  |                  | [type your message ...]   [Send] |
  +------------------+----------------------------------+

1.1  The destination

At the end state we are aiming for, every interaction looks like this:

  - The user speaks or types a request in plain language.
  - The agent gives the LLM two fixed base tools (listApps;
    useApp) plus a per-app set of TYPED tools that are registered
    dynamically when the LLM calls useApp(name) (PART 16). The LLM
    calls listApps() to find available apps, calls useApp(name) to
    load that app's operations as real typed tool definitions (one
    tool per Msg, with proper JSON schemas), then calls those tools
    directly by name - on the user's own apps and, when the
    conversation has been pointed at other users, on theirs too.
  - The LLM picks operations. The agent dispatches the chosen Domatar
    messages on the user's behalf. The platform routes those messages
    exactly as if the user had sent them by hand. The replies feed
    back into the LLM's next turn.
  - The loop continues until the LLM emits a final answer, the
    iteration budget is exhausted, or the user intervenes.
  - Every step is persisted as ordinary objects and links.
    The Navigator and any other interested app see the conversation,
    the tool calls, and their results without any agent-specific code.

The agent has no privileges its caller does not have. It has no
special bypass. Foreign prvs do not trust "the agent" as an identity;
they trust the calling user, exactly as they would for a hand-issued
message. The agent's reach is the user's reach.

1.2  The current state

Today the AI Agent ships a chat-only foundation:

  - The chat window (PART 6) is the user-facing surface.
  - Each user turn is forwarded to an external LLM along with the
    conversation history; the assistant's reply is persisted and
    rendered.
  - The LLM cannot invoke Domatar ops. The user's question is
    answered out of the LLM's training data. There is no tool
    calling, no agent loop, no cross-user access.

This document specifies the destination, marks each section with
its implementation status, and collects the unbuilt pieces in
PART 18 (Deferred Work). Every step from where we are to where we
are going is itself a useful product, and no step requires changes
to apps that don't opt in.

1.3  Constants across the progression

Three properties hold today and must continue to hold at every
milestone:

  1. Domatar-native storage. Conversations, messages, tool calls,
     and tool replies are all real objects linked into the user's
     per-user host. The agent's history is queryable in the same
     way every other Domatar object is queryable. The Navigator
     ([Navigator](Navigator.md) PART 5) sees the agent's tree for free.

  2. External LLM, internal data. The LLM call is an outbound
     HTTPS request to a third-party provider (Groq for v1;
     pluggable). Conversation content, identity, and tool-call
     results live on the user's home prv. Only the message text
     and (eventually) the JSON required for tool-call protocol
     negotiation cross the wire to the LLM provider.

  3. The agent's reach equals the user's reach. Every tool call
     is dispatched as the verified user. The receiving prv runs
     its own verifyLogin; the receiving handler runs its own
     hasRights. Authorization is decided where the data lives,
     never at the agent.

The AI Agent is read-write. Editing a message, deleting a
conversation, sharing a thread with another user, and exporting
the transcript are surfaced in the chat UI; tool-call invocations
that write are gated by the safety policy in PART 17.

## PART 2 — WHY DOMATAR IS UNIQUELY WELL-SUITED FOR AGENTS

A general, world-wide, AI-agent-friendly environment needs a small
set of properties. Domatar provides each of them as a feature of
the substrate, not as something the agent has to invent. The
agent's destination state of PART 1 is reachable because of these
properties; on a different platform it would be a research project.

2.1  Uniform object-message dispatch

Every interaction in Domatar is a JSON envelope addressed to a
DomId ([Domatar](../Domatar.md) PART 2 / PART 5). The dispatcher
(HttpClient.dispatch) decides, per call:

  - Same hstId == this server -> sendLocal in the same JVM.
  - Different hstId           -> sendHttp to the resolved domain.

The agent's tool implementation is one method - msgClient.send(
dstDomId, msg) - regardless of where the target lives. "Use my
object," "use my friend's object on the same prv," and "use a
stranger's public object on a foreign prv" are the same call with
different DomIds. Cross-user reach is not a new code path; it is
the same code path with different addresses.

2.2  Class descriptors as runtime documentation

Per [Class](../platform/Class.md), every class has - or ought to have - a
persistent self-describing object that lists its attributes, the
messages it understands, and (in the extended form this spec
depends on) a natural-language description of each. The agent reads
those descriptors at RUNTIME, through the GetCls tool (PART 16.1),
as it navigates the user's object graph - so the LLM learns what an
app can do at the moment it needs to, without the agent having to
pre-expand every operation into a tool up front (PART 16 preamble).

The same descriptor that documents a class for a human reader in
the Navigator's class panel teaches the LLM how to use it. No app
needs an "agent integration bundle". Agents and humans read the
same schema. An app that wants single-call answers for its common
questions may additionally FEATURE operations for the agent
(PART 16.7), but this is an optimisation over - not a replacement
for - the uniform, self-describing interface.

2.3  Per-object, per-message authorization

Every Domatar handler runs hasRights(inMsg, obj, msgClient) at
the top of every operation ([Domatar](../Domatar.md) PART 6.3).
Authorization is per-object, per-message, evaluated where the
data lives. Public, verified, owner-match, and mixed policies
exist today; richer policies (follower-status, ban-list, ACL)
are each just another branch on the same hook.

For an agent, this is the load-bearing property. The platform
never asks "is this an agent?". It only asks "does the calling
actId have rights to this op on this object?". The agent's
safety boundary is the platform's authorization boundary; that
boundary is enforced at the destination, not at the source.

2.4  Cross-prv re-verification

When a message crosses a prv boundary, the receiving prv runs
its OWN verifyLogin against its OWN account store using the wire
envelope's (usrId, token) ([Domatar](../Domatar.md) PART 6.1). It does
not trust the sender's claim about identity; it derives identity
locally and stamps verified=true|false from its own records.

This is what makes "agent on prv1 reads friend's quips on prv7"
safe. Prv7 sees the calling user's actual credentials, runs its
own quippin handler's hasRights, and grants or denies on its
own terms. There is no notion of "trusting the calling agent"
because there is no notion of "trusting the calling prv" either
- every prv re-verifies. The federation makes the agent inherit
the federation's safety properties for free.

2.5  Federated identity

A user's actId has the form name@appId, where appId is the
issuing application ([Login protocol](Login-Protocol.md)). One sign-in mints a token
that every prv recognises, because every prv asks the same
authority - the issuing app's central host. An agent acting as
Dave on prv1 carries Dave's token; when the agent reaches prv7
in some distant deployment, prv7 verifies Dave's token against
the same authority, gets the same answer, and the agent's
request is treated as Dave's request.

A network-wide agent system needs network-wide identity.
Domatar already has it.

2.6  Persistent object graph as long-term memory

The agent's "memory" is not a special agent-only data structure.
It is the same graph the Navigator browses, persisted in the
same objects and links every other app uses. Cross-conversation
memory (which the LLM cannot maintain across requests) becomes
a query. When account replication / migration lands
([Login protocol](Login-Protocol.md) PART 14 / [Domatar](../Domatar.md) PART 17), the user's
agent history travels with the rest of their data.

2.7  Open application surface

Domatar's AppLoader / class-descriptor model ([Domatar](../Domatar.md)
PART 11) is open: anyone can publish a new app by dropping a
JAR into the apps directory. The agent's reach grows
automatically as the app ecosystem grows: a new app shipped
tomorrow whose handlers carry well-described class descriptors
becomes part of every user's tool catalogue with no change to
the AI Agent itself.

That is the practical sense in which Domatar is "AI-agent-
friendly": the platform turns every well-described class on the
network, on every prv, into a tool the agent can reach for -
subject to the same authorization the human user has - with no
per-app integration work.

## PART 3 — APP IDENTITY

  appId          : "aiagent"
  central host   : "aiagent"   (registered in hst, lives on prv1
                                in the local simulation; same shape
                                as the quippin / login / navigator
                                central hosts).
  per-user host  : aiagent-<actId>   (e.g. aiagent-dave@quippin)
  hosting prv    : the user's home prv (the prv that owns the
                                        user's account).

The host record for aiagent-<actId> is created by ActManagerImpl on
first sign-up via AiagentInstall.install (PART 11).

The central host "aiagent" is registered for parity with the other
apps' central hosts (quippin / login / navigator). v1 uses it only
for the docker-compose alias and as a routing target if a future
"shared catalogue of agents" feature lands (PART 18.7); per-user
data never addresses it.

## PART 4 — THE LLM PROVIDER

4.1  Provider for v1: Groq

Groq Cloud is the v1 provider. Reasons:

  - Free tier sufficient for development:
      ~30 requests/min, ~14k tokens/min on free models.
  - OpenAI-compatible chat-completions API
      (POST /openai/v1/chat/completions, JSON request, JSON reply).
  - OpenAI-compatible function-calling shape, which is the same
    shape the agent's fixed tool schemas use (PART 16.5).
    Switching providers therefore does not require redoing the
    tool-call protocol.
  - Single bearer token in an HTTP header; no OAuth dance.
  - Fast inference (~500 tok/s on small Llama models), so the
    UX is snappy even on free quota.

Sign-up is one form at console.groq.com; the API key is a string
of the form "gsk_..." (~50 chars).

4.2  Provider abstraction

The Java side does NOT bake "Groq" into business logic. A single
LlmClient class encapsulates "given a list of messages and an
optional tool catalogue, return a completion (with optional
tool_calls)". The class is constructed from four env vars:

  AIAGENT_PROVIDER       e.g. "groq"   (default; "ollama" / "gemini"
                                        as PART 18.5 alternatives)
  AIAGENT_API_URL        e.g. "https://api.groq.com/openai/v1/chat/completions"
  AIAGENT_API_KEY        e.g. "gsk_..."  (the bearer token; never
                                          stored on objects)
  AIAGENT_MODEL          e.g. "llama-3.3-70b-versatile"

A switch on AIAGENT_PROVIDER maps to one of {GroqAdapter,
OllamaAdapter, GeminiAdapter}. v1 ships GroqAdapter only; the
other two are PART 18.5 deferred work, but the adapter interface
is locked in v1 so adding them is one new file plus one switch
case.

4.3  Why an env var, not the database

API keys are SECRETS. Storing them on ordinary objects - which are
queryable by any verified caller via Open / GetObj, and replicable
across prvs - is the wrong shape. They live exclusively in the
Tomcat process's environment, set by docker-compose, and read
once at servlet init.

Per-user-supplied keys (a user pays for their own quota) are
deferred (PART 18.5). They require explicit encryption-at-rest
on a per-user basis (existing pwd-style storage in act.Pwd is
the same problem) plus per-user JCE wrapping.

## PART 5 — DATA MODEL

The AI Agent reuses the object and link conventions every other Domatar
app uses ([Domatar](../Domatar.md) PART 7): one singleton container per
logical collection, one object per item, with persistent lnks
linking them so the Navigator can traverse the tree.

Implementation status: 5.1, 5.2 (without Mode/Policy/budget
attrs), and 5.3 (without Role="tool") are implemented. The agent
loop additions (Mode, Policy, Role="tool" message variants) are
deferred, PART 18.1 / 18.3.

5.1  Containers

  Conversations container
  -----------------------
    HstId    = aiagent-<actId>
    AppId    = aiagent
    ActId    = <actId>
    ObjId    = conversations          (singleton)
    ClsAppId = aiagent
    ClsId    = conversations
    ObjName  = "Conversations"
    ObjDesc  = "Your AI chat threads"
    Attrs    = {}

5.2  Conversation rows

One row per chat thread. Created on the user's first message in
a new conversation, or by an explicit NewConversation op.

    HstId    = aiagent-<actId>
    AppId    = aiagent
    ActId    = <actId>
    ObjId    = conv-<base64-time>     (e.g. conv-0OabcXyZ)
    ClsAppId = aiagent
    ClsId    = conv
    ObjName  = <Title>                (e.g. "Sky thoughts" - first
                                       N chars of the user's first
                                       message; user-renameable later)
    ObjDesc  = <one-liner>            (defaults to "")
    Attrs    = {
      "Title"        : "Sky thoughts",
      "Model"        : "llama-3.3-70b-versatile",
      "SystemPrompt" : "You are a helpful assistant.",
      "CreatedAt"    : "1714838400000",
      "UpdatedAt"    : "1714838420000",
      "MessageCount" : "4",
      "TokensIn"     : "320",         (running total across all turns)
      "TokensOut"    : "180",

      "Mode"         : "Chat" | "ReadOnly" | "Agent",   (PART 17.1)
      "Policy"       : "<JSON>",                         (PART 17.1)

      "ForeignActIds": "[\"dave@quippin\", ...]"         (PART 17.5;
                                                          stringified
                                                          JSON array)
    }

The "Mode", "Policy", and "ForeignActIds" attrs are part of the
target state. Today's conv rows do not carry them; the loop
treats their absence as Mode="Chat" with empty Policy.

5.3  Message rows

One row per message in a conversation. User turns, assistant
turns, AND tool-call records (the agent's destination state) are
all messages. System prompts are NOT messages (they live on the
conv row's SystemPrompt attr so they are not duplicated per
turn).

    HstId    = aiagent-<actId>
    AppId    = aiagent
    ActId    = <actId>
    ObjId    = msg-<base64-time>      (timestamp-derived; sorts
                                       chronologically as a string)
    ClsAppId = aiagent
    ClsId    = msg
    ObjName  = <first 40 chars of Text> (Obj.objName cap is 40)
    ObjDesc  = <Role>: <first 100 chars> (e.g. "user: Why is the
                                          sky...")
    Attrs    = {
      "ConvId"     : "conv-0OabcXyZ", (the parent conv's ObjId,
                                       redundant with the lnk but
                                       cheap to denormalise)
      "Role"       : "user" | "assistant" | "tool",
      "Text"       : "<full message body>",
      "Time"       : "<unix-ms>",
      "Model"      : "<model id; only set on assistant turns>",
      "TokensIn"   : "<int; only set on assistant turns>",
      "TokensOut"  : "<int; only set on assistant turns>",
      "FinishReason": "stop" | "length" | "content_filter" | "error"
                      | "tool_calls" | "rejected_by_user"
                      | "denied_by_target",

      // Tool-call additions (Role="tool" only; PART 15.4):
      "ToolName"      : "<ClsAppId>.<ClsId>.<MsgName>",
      "ToolTargetSov" : "<stringified destination DomId>",
      "ToolArgs"      : "<stringified JSON arguments>",
      "ToolResult"    : "<stringified JSON reply>"
    }

The Text attr is the AUTHORITATIVE message body. ObjName / ObjDesc
are denormalised previews so the Navigator's tree shows useful
labels without reading every full message. For Role="tool"
messages the Text is a human-readable summary of the tool call
and its outcome ("called bookstore.forsale.GetForSale on
bookstore-dave@quippin -> 12 listings"); the structured payload
lives in the Tool* attrs.

5.4  Lnks

  Source: navigator-<actId>.navigator.<actId>.app-aiagent
    -> aiagent-<actId>.aiagent.<actId>.conversations    tag=("navigator","container")

  Source: aiagent-<actId>.aiagent.<actId>.conversations
    -> aiagent-<actId>.aiagent.<actId>.conv-<id>        tag=("aiagent","conv")
       (one lnk per conversation; SeqNum=conv.CreatedAt so the
        Navigator lists conversations in chronological order)

  Source: aiagent-<actId>.aiagent.<actId>.conv-<id>
    -> aiagent-<actId>.aiagent.<actId>.msg-<id>         tag=("aiagent","msg")
       (one lnk per message in the conversation - including
        Role="tool" messages; SeqNum=msg.Time so the Navigator
        and the chat UI both render in order)

The conv -> msg lnks are the primary read path for both the
Navigator (visualisation) and the chat UI (history-for-completion).
MaxLnks=500 by default is plenty for v1 conversations; pagination
is deferred (PART 18.6).

## PART 6 — WEB UI (aiagent.html)

6.1  URL

  /<context>/aiagent
      JSP-mapped to aiagent.html via web.xml, exactly like
      /<context>/desktop -> desktop.html. v1 ships under the
      shared /quippin/ context.

  /<context>/AgentWui
      The single Wui endpoint backing the chat page (PART 7).

No Quippin side menu - same rule as Desktop / Account / Navigator.

6.2  Layout

A two-pane layout, left fixed-width (collapsible on narrow
viewports), right fluid:

  +------------------+----------------------------------+
  |                  | <conversation title>      [Mode] |
  |  Conversations   |                                  |
  |                  | message-list (scrolling)         |
  |  [+ New chat]    |                                  |
  |                  |   user: ...                      |
  |  - Sky thoughts  |   assistant: ...                 |
  |  - Recipe ideas  |   [tool] called X on Y           |
  |                  |   assistant: ...                 |
  |                  |                                  |
  |  [Sign out]      | [text area ............ ] [Send] |
  +------------------+----------------------------------+

6.3  Page flow

On load:
  1. POST /<context>/AgentWui  { Action=ListConversations }
  2. Render returned Conversations[] in the left pane, sorted by
     UpdatedAt descending (newest first).
  3. If the list is non-empty, auto-select the most recent
     conversation: POST { Action=GetConversation, ConvId=... }
     and render its messages on the right.
     If the list is empty, show a "Start a new chat" placeholder
     in the right pane.

User clicks "+ New chat":
  - The right pane becomes a fresh, empty message-list with the
    text area enabled.
  - No server call yet. The conversation is materialised on the
    server only when the user sends their first message (PART 7,
    SendMessage with no ConvId -> server creates one).

User selects a conversation in the left pane:
  - POST { Action=GetConversation, ConvId=... } and render.
  - The page caches each loaded conversation locally so re-clicking
    is free; a Refresh button (6.5) clears the cache.

User types and clicks Send:
  - POST { Action=SendMessage, ConvId=<current or empty>,
                                Text=<typed text> }
  - Disable the text area and show "..." typing indicator.
  - On reply: append the user message, every Role="tool" message
    the server emitted during the agent loop (collapsed by
    default; expandable to show args/result), and the final
    assistant message. Re-enable the text area.
  - On error: show the error in a red banner above the text area;
    leave the text in the box so the user can retry without
    re-typing.

In Agent mode, the response may include a pending-confirmation
flag for a write tool; in that case the chat pane shows a
confirmation card (PART 17.3) and re-enables the text area only
after the user resolves it (Approve / Reject).

6.4  Streaming (deferred)

v1 is non-streaming: SendMessage blocks until the LLM returns the
full assistant reply, then both turns appear together. Streaming
(server-sent events; the assistant's text grows token-by-token)
is deferred (PART 18.5). The UX hit is small on Groq because
inference is fast; on slower providers (Ollama on a laptop CPU)
streaming will matter, and once the agent loop runs multiple
LLM round-trips per turn (tool calling) streaming the
intermediate messages becomes part of the experience.

6.5  Toolbar

In the right pane's header:

  Refresh             Re-fetch the current conversation from the
                      server, invalidating the local cache.
  Rename              Inline-rename the conversation Title.
                      POST { Action=RenameConversation, ConvId,
                             Title }.
  Delete              POST { Action=DeleteConversation, ConvId }.
                      Confirms first; on success, removes the entry
                      from the left pane and selects the next-newest.
  Mode                A small selector (Chat / ReadOnly / Agent)
                      that POSTs SetMode for the current conv.
                      Switching out of Chat the first time shows
                      a one-time explanation of what changes.
  Settings            Opens the Policy panel (PART 17.1): allow /
                      deny lists, foreign actIds, budgets,
                      pre-approved writes.
  Sign out            Same as Account's Sign out.

The Mode selector and Settings panel are part of the destination
state; v1 ships only Refresh / Rename / Delete / Sign out.

6.6  Right-pane rendering

Each user / assistant message renders as one block:

    +-----------------------------------------------+
    |  user            HH:MM                        |
    |  Your message text, preserving newlines.      |
    +-----------------------------------------------+

    +-----------------------------------------------+
    |  assistant       HH:MM    llama-3.3-70b       |
    |  The model's reply, preserving newlines.      |
    +-----------------------------------------------+

A Role="tool" message renders as a collapsed action bar:

    +-----------------------------------------------+
    |  > called bookstore.forsale.GetForSale        |
    |    on bookstore-dave@quippin (12 listings)    |
    +-----------------------------------------------+

The user clicks the bar to expand it into the full call: target
DomId, arguments JSON, reply JSON, finish reason. This makes
every agent action inspectable.

In Agent mode, an unresolved write proposal renders as a
confirmation card (PART 17.3):

    +-----------------------------------------------+
    |  ! agent wants to call                        |
    |    bookstore.forsale.ListBook                 |
    |    on bookstore-<actId>                       |
    |    args: { Title: "...", Price: "$12.50" }    |
    |    [Approve once]  [Approve always]  [Reject] |
    +-----------------------------------------------+

Markdown rendering (code blocks, **bold**, lists) is deferred
(PART 18.5). v1 renders Text as plain text with HTML-escaping
and \n preserved.

## PART 7 — WUI SERVLET (AgentWui)

  com.aiagent.webui.AgentWui  @WebServlet("/AgentWui/*")
  extends com.domatar.servlet.DomatarServlet

Per-action request handling. Every action's dst is the user's
own conversations container or a specific conv row, both of which
live on the user's home prv -> sendLocal.

  Action=ListConversations
      Body params: (none)
      dst = (aiagent-<srcActId>, aiagent, <srcActId>, conversations)
      addRequestBody("ListConversations", null)
      addClsId("aiagent", "conversations")

  Action=GetConversation
      Body params: ConvId  (required)
      dst = (aiagent-<srcActId>, aiagent, <srcActId>, <ConvId>)
      addRequestBody("GetConversation", null)
      addClsId("aiagent", "conv")

  Action=SendMessage
      Body params: ConvId  (optional - omit/empty for "new
                            conversation"), Text (required)
      If ConvId is empty:
          dst = (aiagent-<srcActId>, aiagent, <srcActId>, conversations)
          addClsId("aiagent", "conversations")
          (The conversations container handles "create + send".)
      Else:
          dst = (aiagent-<srcActId>, aiagent, <srcActId>, <ConvId>)
          addClsId("aiagent", "conv")
      addRequestBody("SendMessage", { Text, ConvId? })

  Action=RenameConversation
      Body params: ConvId, Title
      dst = the conv row.
      addClsId("aiagent", "conv")

  Action=DeleteConversation
      Body params: ConvId
      dst = (aiagent-<srcActId>, aiagent, <srcActId>, <ConvId>)
      addRequestBody("DeleteConversation", null)
      addClsId("aiagent", "conv")
      (The conv row owns its own deletion; PART 8.2.)

The destination state adds:

  Action=SetMode
      Body params: ConvId, Mode ("Chat" | "ReadOnly" | "Agent")
      dst = the conv row.

  Action=SetPolicy
      Body params: ConvId, Policy (stringified JSON; PART 17.1)
      dst = the conv row.

  Action=ApproveToolCall
      Body params: ConvId, PendingId, Always (boolean)
      dst = the conv row.
      Resumes a paused agent loop with the user's verdict
      (PART 15.2 / PART 17.3). When Always=true, the
      (ClsAppId.ClsId.MsgName) is added to the conv's
      PreApprovedWrites.

  Action=RejectToolCall
      Body params: ConvId, PendingId
      dst = the conv row.
      Resumes the loop with FinishReason="rejected_by_user".

AgentWui does NOT verify credentials itself - DomatarServlet's
outer dispatch already does that, populating Context with the
verified actId/usrId.

Implementation status: ListConversations, GetConversation,
SendMessage, RenameConversation, DeleteConversation are
implemented. SetMode, SetPolicy, ApproveToolCall, RejectToolCall
are deferred (PART 18.1, 18.3).

## PART 8 — OBJ HANDLERS

8.1  ConversationsImpl

  com.aiagent.objimpl.ConversationsImpl  extends ObjImpl
  Routed by ImplMap entry (aiagent, conversations) -> ConversationsImpl.

  Authorization: verified-only (PART 12).

  Operations:

    ListConversations(opr, inMsg, outMsg)
        objs = ObjDb.getObjPrefix(dst.hstId, "aiagent", dst.actId,
                                  "conv", null, 1000)
        For each, project { ConvId=obj.domId.objId,
                            Title=attrs.Title,
                            CreatedAt=attrs.CreatedAt,
                            UpdatedAt=attrs.UpdatedAt,
                            MessageCount=attrs.MessageCount,
                            Model=attrs.Model,
                            Mode=attrs.Mode }.
        Reply: { Conversations: [ ... sorted desc by UpdatedAt ... ] }

    SendMessage(opr, inMsg, outMsg)        # the "new conversation" path
        text = inMsg.getAttr("Text"). Required.
        Generate convId = IdGen.createIdFromCurTime("conv").
        Build SystemPrompt = default ("You are a helpful assistant.").
        Build conv obj with Title=first 40 chars of text, CreatedAt=now,
        UpdatedAt=now, MessageCount=0, Model=AIAGENT_MODEL,
        TokensIn=0, TokensOut=0, Mode="Chat", Policy="{}".
        ObjDb.addObj(convObj).
        LnkDb.addLnk(<conversations container> -> <conv>,
                     tag=("aiagent","conv"), seqNum=now).
        Then call doSendMessage(convDomId, text, convObj.attrs)
        (the same method ConvImpl uses; PART 8.2). The reply already
        contains all turns produced this round, so just forward it
        with an extra ConvId attr for the browser to remember.

    (DeleteConversation lives on ConvImpl; PART 8.2.)

8.2  ConvImpl

  com.aiagent.objimpl.ConvImpl  extends ObjImpl
  Routed by ImplMap entry (aiagent, conv) -> ConvImpl.

  Authorization: verified-only + owner-match (the calling actId
  must equal dst.actId; otherwise "Not authorized"). The chat
  history is sensitive; even a verified user on a peer prv must
  not read someone else's conversation. [Login protocol](Login-Protocol.md) PART 7
  already exposes Auth.isOwner(inMsg) for this.

  Operations:

    GetConversation(opr, inMsg, outMsg)
        msgs = ObjDb.getObjPrefix(dst.hstId, "aiagent", dst.actId,
                                  "msg-", null, 1000) filtered by
        attrs.ConvId == dst.objId. (Faster path: walk the conv's
        outgoing lnks via obj.getLnks(... tag="msg" ...) and pull
        the msg objects by DomId. v1 ships the simple prefix scan;
        the lnk-based path is a one-line refactor.)
        Sort by attrs.Time ascending.
        Project each msg as { Role, Text, Time, Model, TokensIn,
                              TokensOut, FinishReason,
                              ToolName?, ToolTargetSov?,
                              ToolArgs?, ToolResult? }.
        Reply: { Title=conv.Title, Model=conv.Model,
                 Mode=conv.Mode, Policy=conv.Policy,
                 Messages=[...] }.

    SendMessage(opr, inMsg, outMsg)
        text = inMsg.getAttr("Text"). Required.
        doSendMessage(dst, text, conv.attrs).
        Reply: { ConvId, Title, Messages: [...turns produced this
                 round, in order: user, then any tools, then
                 assistant - or a pending-confirmation marker
                 when the loop is paused awaiting user approval] }.

    RenameConversation(opr, inMsg, outMsg)
        title = inMsg.getAttr("Title"). Required.
        Modify conv.attrs.Title = title; ObjDb.modifyObj(...).
        Reply: { Renamed: "True" }.

    SetMode (destination state)
        mode = inMsg.getAttr("Mode"). One of
               "Chat" / "ReadOnly" / "Agent".
        Modify conv.attrs.Mode; ObjDb.modifyObj(...).
        Reply: { Mode: mode }.

    SetPolicy (destination state)
        policyJson = inMsg.getAttr("Policy").
        Validate against the Policy schema (PART 17.1).
        Modify conv.attrs.Policy; ObjDb.modifyObj(...).
        Reply: { Saved: "True" }.

    ApproveToolCall / RejectToolCall (destination state)
        See PART 15.2.

    DeleteConversation(opr, inMsg, outMsg)
        # The conv deletes itself and everything it owns.
        # dst = the conv DomId; conversations container is computable
        # as (dst.hstId, dst.appId, dst.actId, "conversations").
        DomId convsId = new DomId(dst.hstId, "aiagent", dst.actId,
                                  "conversations");
        # 1. Delete every msg row + every conv->msg lnk
        msgs = ObjDb.getObjPrefix(dst.hstId, "aiagent", dst.actId,
                                  "msg", null, 10000)
                  filtered by attrs.ConvId == dst.objId.
        For each msg:
          ObjDb.deleteObj(msg.domId).
          LnkDb.deleteLnks(<conv>, msg.domId, "aiagent", "msg",
                           null, null).
        # 2. Delete the conv row + the conversations->conv lnk
        ObjDb.deleteObj(dst).
        LnkDb.deleteLnks(convsId, dst, "aiagent", "conv", null, null).
        # 3. Reply
        outMsg: { Deleted: "True", ConvId: dst.objId }.

        Range deletion of msgs would benefit from a ConvId-indexed
        column; PART 18.6. Bulk-delete via MaxN=10000 is fine for
        v1.

  Shared method:

    doSendMessage(convDomId, text, convAttrs)

      v1 (chat-only) algorithm:
        # 1. Append the user message
        msgIdU = IdGen.createIdFromCurTime("msg").
        userMsg = build msg obj with Role="user", Text=text,
                  Time=now, ConvId=convDomId.objId, ObjName=
                  first 40 chars of text, ObjDesc="user: " +
                  first 100 chars.
        ObjDb.addObj(userMsg).
        LnkDb.addLnk(<conv> -> <userMsg>, tag=("aiagent","msg"),
                     seqNum=now).

        # 2. Read conversation history
        history = ObjDb.getObjPrefix(... "msg-" ...) filtered by
                  ConvId, sorted by Time. (The user message we
                  just wrote is included.)

        # 3. Call the LLM
        llmMessages = [ {role="system", content=convAttrs.SystemPrompt} ]
                    + [ {role=m.Role, content=m.Text} for m in history ]
        llmResult = LlmClient.complete(convAttrs.Model, llmMessages)
        # llmResult = { text, tokensIn, tokensOut, finishReason }

        # 4. Append the assistant message
        msgIdA = IdGen.createIdFromCurTime("msg").
        assistantMsg = build msg obj with Role="assistant",
                       Text=llmResult.text, Time=now,
                       ConvId=convDomId.objId,
                       Model=convAttrs.Model,
                       TokensIn=llmResult.tokensIn,
                       TokensOut=llmResult.tokensOut,
                       FinishReason=llmResult.finishReason.
        ObjDb.addObj(assistantMsg).
        LnkDb.addLnk(<conv> -> <assistantMsg>, tag=("aiagent","msg"),
                     seqNum=now+1).

        # 5. Update conv counters
        Modify conv.attrs.MessageCount += 2,
               conv.attrs.UpdatedAt = now,
               conv.attrs.TokensIn  += tokensIn,
               conv.attrs.TokensOut += tokensOut.
        ObjDb.modifyObj(...)

        # 6. Return both messages to the caller
        Reply with both serialised messages.

      Destination-state algorithm (PART 15):
        # Step 1 (append user message) is identical.
        # Step 2 (read history) is identical, but tool messages
        #         are included in history - they are part of the
        #         model's context.
        # Step 3 is replaced by the agent loop (PART 15.2).
        # The loop may produce zero or more Role="tool" messages
        # before producing the final Role="assistant" message,
        # OR may pause awaiting user approval of a write
        # (PART 17.3). Each persisted message updates the conv
        # counters in the same shape as step 5.
        # Step 6 returns ALL turns produced this round.

8.3  MsgImpl

  com.aiagent.objimpl.MsgImpl  extends ObjImpl
  Routed by ImplMap entry (aiagent, msg) -> MsgImpl.

  Authorization: verified-only + owner-match. The chat content is
  sensitive; addressing a single msg row directly (e.g. via the
  Navigator's Open on a msg-* DomId) must be gated even though
  most reads come through ConvImpl.GetConversation. The hasRights
  override is the same shape as ConvImpl's:

    public boolean hasRights(JsonMsg inMsg, Obj obj, DomatarMsgClient msgClient)
    {
      return Auth.isVerified(inMsg) && Auth.isOwner(inMsg);
    }

  Operations: none of its own. Open and GetObj are inherited from
  ObjImpl unchanged - msg rows are inert storage; MsgImpl exists
  purely to enforce the per-row authorization gate. Per-msg ops
  (Edit, Regenerate, Delete-just-this-msg) are deferred
  (PART 18.6).

## PART 9 — THE LlmClient

9.1  Interface

  com.aiagent.llm.LlmClient
      public static LlmResult complete(String model,
                                       List<LlmMessage> messages,
                                       List<LlmTool>    tools,
                                       String           toolChoice)
          throws DomatarException;

      public static class LlmMessage {
          public String role;     // "system" | "user" | "assistant"
                                  // | "tool"
          public String content;
          public String toolCallId;   // populated for role="tool"
                                      // replies; matches a prior
                                      // tool_call's id
          public List<LlmToolCall> toolCalls;  // populated when an
                                               // assistant turn is
                                               // a tool_call request
      }

      public static class LlmTool {
          public String name;          // fixed verb (PART 16.1) or,
                                       // when featured projection is on,
                                       // "<ClsAppId>.<ClsId>.<MsgName>"
          public String description;
          public String parametersSchema;  // JSON Schema string
                                           // (PART 16.5)
      }

      public static class LlmToolCall {
          public String id;
          public String name;
          public String argumentsJson;
      }

      public static class LlmResult {
          public String text;
          public List<LlmToolCall> toolCalls;
          public int    tokensIn;
          public int    tokensOut;
          public String finishReason;   // "stop" | "length"
                                        // | "content_filter"
                                        // | "tool_calls" | "error"
      }

The "tools" and "toolChoice" parameters are part of the destination
shape. v1's LlmClient.complete takes only (model, messages); the
extended signature is the destination state. Adding the
overload, plus the LlmTool / LlmToolCall types, is one of the
units of work for PART 18.1.

9.2  Init

LlmClient reads four env vars at static-init time:

  AIAGENT_PROVIDER   default "groq"
  AIAGENT_API_URL    default "https://api.groq.com/openai/v1/chat/completions"
  AIAGENT_API_KEY    no default; throws on first call if unset
  AIAGENT_MODEL      default "llama-3.3-70b-versatile"
                     (free, fast, large enough for chat;
                      the conv obj's Attrs.Model can override)

If AIAGENT_API_KEY is missing, LlmClient.complete throws
DomatarException("LLM not configured: AIAGENT_API_KEY missing").
ConvImpl translates this into an assistant message with
Role="assistant", Text="(error: LLM not configured)",
FinishReason="error" so the user sees a clean failure in the UI
rather than a blank reply. Same pattern for transport errors,
HTTP 5xx, rate-limit (429), and content-filter responses.

9.3  GroqAdapter

Outbound HTTP via java.net.http.HttpClient (built into JDK 11+,
already on the Tomcat classpath). Request:

  POST https://api.groq.com/openai/v1/chat/completions
  Authorization: Bearer <AIAGENT_API_KEY>
  Content-Type: application/json
  Body:
    {
      "model"      : "<model>",
      "messages"   : [ {"role":"system","content":"..."},
                       {"role":"user","content":"..."},
                       {"role":"assistant","content":"...",
                        "tool_calls":[{"id":"...","type":"function",
                                       "function":{"name":"...",
                                       "arguments":"..."}}]},
                       {"role":"tool","tool_call_id":"...",
                        "content":"..."} ],
      "tools"      : [ {"type":"function",
                        "function":{"name":"...",
                                    "description":"...",
                                    "parameters": <JSON Schema>}} ],
      "tool_choice": "auto" | "none" | {"type":"function",...},
      "max_tokens" : 1024,
      "temperature": 0.7
    }

Response (success):

    {
      "choices" : [
        { "message" : {"role":"assistant",
                       "content":"<the reply text>" or null,
                       "tool_calls":[{"id":"...","type":"function",
                                       "function":{"name":"...",
                                       "arguments":"..."}}]?},
          "finish_reason" : "stop" | "tool_calls" | ... }
      ],
      "usage" : { "prompt_tokens": 320, "completion_tokens": 180 }
    }

GroqAdapter parses the JSON, extracts choices[0].message.content,
choices[0].message.tool_calls, choices[0].finish_reason,
usage.prompt_tokens, usage.completion_tokens, and returns an
LlmResult.

Timeout: 60 seconds (Groq is fast; a 60 s deadline is generous).
Retries: none in v1 - bubble up errors. Retry-with-backoff on 429
is deferred (PART 18.5).

Implementation status: GroqAdapter speaks the chat-completion
shape today (no tools / tool_calls). The tool-calling extension
is part of the destination state.

9.4  Other adapters (deferred, PART 18.5)

  OllamaAdapter  -> POST http://ollama:11434/api/chat - Ollama
                    speaks a slightly different JSON (response is
                    streamed by default; non-streaming mode set
                    via "stream":false). Recent Ollama versions
                    also support the OpenAI tool-calling shape.

  GeminiAdapter  -> POST https://generativelanguage.googleapis.com/
                         v1beta/models/<model>:generateContent
                    - request and response shape differ from
                    OpenAI's; adapter normalises. Gemini speaks
                    "function calling" with its own schema; the
                    fixed tool schemas (PART 16.5) map cleanly.

The adapter interface accommodates all three; only the per-adapter
wire format differs.

## PART 10 — CROSS-USER AND CROSS-PRV REACH

10.1 The agent acts as the verified user

Every tool call the agent issues is dispatched with the calling
user's Context (the same Context built when the user's HTTP
request hit DomatarServlet). The wire envelope carries the user's
(usrId, token). The platform's routing makes no distinction
between an agent-issued message and a hand-issued one; the agent
is just code that emits messages on a verified user's behalf.

10.2 Cross-prv crossings re-verify

When the agent issues a tool call to a DomId on another prv,
HttpClient.dispatch ([Domatar](../Domatar.md) PART 5) takes the sendHttp
path. The receiving prv's Msg.doAction runs verifyLogin against
ITS account store and stamps verified=true|false based on its own
records ([Domatar](../Domatar.md) PART 6.1). The receiving handler's
hasRights then decides admit/deny.

This is the same path used by every cross-prv interaction in
Domatar. The agent does not require any new authentication,
identity, or trust mechanism. It rides the existing rails.

10.3 Worked example: agent reads a friend's public quips

User in conversation with the agent: "What has dave@quippin
posted today?"

  1. Catalogue assembly (PART 16) notices dave@quippin in
     ForeignActIds. The agent issues a GetCls(quippin.quips)
     against the quippin-dave@quippin sub-host (a Read tool,
     Auth=Public). Reply includes the GetQuips message schema.

  2. The agent surfaces GetQuips as a tool to the LLM. The LLM
     calls it with a "since=midnight" parameter.

  3. msgClient.send(dstDomId=(quippin-dave@quippin, quippin,
     dave@quippin, quips), op=GetQuips, args={since:...}).
     dst.hstId != prv1's hstId, so HttpClient takes the sendHttp
     path. The directory says quippin-dave@quippin is on prv2.
     HTTP POST to prv2/domatar/Msg.

  4. Prv2's Msg.doAction runs verifyLogin for the calling user
     against its account store. The user is verified (because
     accounts are issued by quippin's central host and that
     authority's verdict is the same on both prvs). verified=true.

  5. ImplMap (quippin, quips) -> QuipsImpl. QuipsImpl.GetQuips
     has Auth="Public" (anyone can read a user's public quips).
     It returns the quip list.

  6. The reply travels back. The agent appends a Role="tool"
     message to the conv with the JSON. The LLM reads it and
     produces a natural-language answer.

If the same op had been declared Auth="Follower", step 5 would
have checked whether the calling user follows dave@quippin and
rejected the call if not. The agent would have surfaced "Not
authorized" to the LLM, which would explain to the user that it
cannot see dave's data without being followed.

10.4 Worked example: agent posts to a shared workspace

If the conversation is in Agent mode and PreApprovedWrites
contains "bookstore.forsale.ListBook":

  1. The LLM proposes ListBook with concrete parameters.

  2. The agent dispatches msgClient.send(dstDomId=
     (bookstore-<actId>, bookstore, <actId>, forsale),
     op=ListBook, args=...). Local. ForSaleImpl.ListBook checks
     Auth=Owner (caller's actId == dst.actId), passes, writes the
     listing, calls into the catalog (which lands cross-prv on
     the bookstore catalog host - PART 10.2 path).

  3. Tool result comes back; the LLM informs the user that the
     listing was posted.

If ListBook were not pre-approved, step 1 would surface a
confirmation card (PART 17.3) before step 2.

10.5 Discovery of foreign DomIds

The agent does not magically know every DomId on the network.
Discovery is itself a graph traversal:

  - The user's Navigator root tells the agent what is locally
    relevant (the user's installed apps, their containers, their
    objects).

  - Cross-user discovery candidates come from links the user
    already has - follows lists, shared-with lists, the Quippin
    Directory, the global hosts directory ([Domatar](../Domatar.md)
    PART 4.2). All of these are themselves Domatar objects with
    their own classes; the agent reaches them through tool calls.

  - The federated app catalog ([Domatar](../Domatar.md) PART 9.2
    Direction) is the natural starting point for "what apps and
    classes exist on the network at all" queries. As that lands,
    agent discovery improves correspondingly.

The conversation's ForeignActIds list (PART 17.5) is what tells
the agent which foreign actIds are in scope for the current
conversation; the agent does not propose foreign tools for actIds
not in scope.

Implementation status: PART 10 is the destination behaviour. v1
has no agent loop and so issues no cross-user tool calls, but
the platform-level routing it would use is fully implemented and
exercised by every other cross-prv message in the system today.

## PART 11 — APP INSTALL AND BOOTSTRAP

The AI Agent's per-user surface is materialised by AiagentInstall,
following the pattern [Navigator](Navigator.md) PART 10 establishes for
Quippin / Login / Desktop / Navigator.

11.1 AiagentInstall.install

  com.aiagent.install.AiagentInstall
      public static void install(String actId,
                                 String usrName,
                                 String domain,
                                 String prvId)
        throws DomatarException

  1. HstDb.addHst(aiagent-<actId>, domain, prvId)         (idempotent)
  2. ObjDb.addObj(<conversations>) on aiagent-<actId>:
       clsAppId=aiagent, clsId=conversations,
       objName="Conversations",
       objDesc="Your AI chat threads", attrs={}.
  3. ObjDb.addObj(<app-aiagent>) on navigator-<actId>:
       clsAppId=navigator, clsId=app,
       objName="AI Agent",
       objDesc="Chat with an AI",
       attrs={}.
  4. LnkDb.addLnk(<root> -> <app-aiagent>) (NavigatorInstall
     created <root>; this lnk is the agent's slot in the
     Navigator tree).
  5. LnkDb.addLnk(<app-aiagent> -> <conversations>)
     tag=("navigator","container").

  Destination state additionally:

  6. Create the (domatar, cls) descriptor objects for
     (aiagent, conversations), (aiagent, conv), and (aiagent, msg)
     on navigator-<actId> with the enriched description /
     conventions / per-Msg description fields the agent needs
     (PART 16.2 / [Class](../platform/Class.md)). Today the install creates
     descriptor objects only for some classes; PART 18.4 covers
     bringing the full enrichment online for all of them.

Every step idempotent. Re-running is a no-op.

11.2 ActManagerImpl integration

ActManagerImpl.addAct's root sign-up branch runs install routines
in order:

  NavigatorInstall.install(...)
  QuippinInstall  .install(...)
  LoginInstall    .install(...)
  DesktopInstall  .install(...)
  AiagentInstall  .install(...)

Order doesn't matter relative to Quippin/Login/Desktop because
none of them lnk into aiagent or vice versa; they all lnk only
to the navigator skeleton, which NavigatorInstall has already
written by then.

For LINK-mode AddAct, AiagentInstall is NOT re-run (same rule the
other installs follow).

11.3 Legacy users (SQL backfill)

mySQL/dump-2024-01-20-aiagent.sql:

  - hosts:
      aiagent                  -> tomcat1:8080, prv1
      aiagent-dave@quippin     -> tomcat1:8080, prv1
      aiagent-micha@quippin    -> tomcat2:8080, prv2

  - objects on aiagent-<actId> (per user):
      conversations

  - objects on navigator-<actId> (per user):
      app-aiagent

  - lnks (per user):
      navigator-<actId>.root         -> app-aiagent
      navigator-<actId>.app-aiagent  -> aiagent-<actId>.conversations

  - desktop catalog row for the AI Agent tile (PART 13.1) on
    desktop-<actId>.apps for each existing user.

Per-user conversations / messages are NOT seeded - users start
with an empty agent. That's correct: chat history is something
they create.

## PART 12 — AUTHORIZATION

12.1 Layers

  Verified  : the caller has a valid session ([Login protocol](Login-Protocol.md)
              PART 6 already establishes this on every inbound
              request). Required for every Agent op except (none).
  Owner     : the caller's actId == dst.actId. Required for every
              op that reads or writes per-user data, which in v1
              is every op.

12.2 Implementation

  ConversationsImpl.hasRights(...) returns
      Auth.isVerified(inMsg) && Auth.isOwner(inMsg);
  ConvImpl.hasRights(...)       returns the same.
  MsgImpl.hasRights(...)        returns the same.

The single caller of GetConversation / SendMessage in v1 is the
user themselves, on their own conv. Sharing a conversation with
another user is deferred (PART 18.7); until then, owner-match is
the right v1 default.

12.3 The agent's authorization story

The agent itself is NOT a separate identity. When the agent
issues a tool call on the user's behalf, it builds the dispatch
Context from the calling user's verified session and the wire
envelope carries the user's (usrId, token).

Authorization on the receiving end - whether the receiving handler
lives on the user's own host or on a foreign user's host - is
unchanged from any other call: each handler runs its own
hasRights() against the verified caller. There is no agent-only
permission, no agent-only policy, no agent-only bypass. The
existing (verified / owner-match / mixed / future-richer)
authorization machinery is the agent's authorization machinery.

This is why the agent's reach equals the user's reach (PART 1.3
constant 3, restated): the platform never asks "is this an
agent?", and so cannot grant the agent anything more than the
user has.

## PART 13 — POST-LOGIN INTEGRATION (Desktop tile)

13.1 Catalog entry

The AI Agent gets one tile in Desktop's seeded catalog
([Desktop](Desktop.md) PART 8). AppsImpl.seedDefaultCatalog grows a
fourth row:

  Obj seed for app-aiagent on desktop-<actId>
  -------------------------------------------
    HstId       = desktop-<actId>
    AppId       = desktop
    ActId       = <actId>
    ObjId       = app-aiagent
    ClsAppId    = desktop
    ClsId       = app
    ObjName     = "AI Agent"
    ObjDesc     = "Chat with an AI"
    Attrs       = {
      "DisplayName": "AI Agent",
      "IconPath":    "/quippin/icons/aiagent.svg",
      "LaunchPath":  "/quippin/aiagent",
      "Position":    "4"
    }

The icon asset (aiagent.svg, a stylised speech bubble on a chip
or a small robot face on a rounded-square tile) lives in
src/main/webapp/icons/.

13.2 Hst rows

Per PART 11.1 / 11.3:

  ('aiagent',                'tomcat1:8080', 'prv1', ...),
  ('aiagent-dave@quippin',   'tomcat1:8080', 'prv1', ...),
  ('aiagent-micha@quippin',  'tomcat2:8080', 'prv2', ...);

13.3 Docker compose

tomcat1 wears a sixth hat on domatar_net: alias "aiagent"
alongside the existing five (domatar / tomcat1 / quippin / login
/ navigator). One-line edit to docker-compose.yml.

In addition, both tomcats need three new env vars:

  AIAGENT_PROVIDER: "groq"
  AIAGENT_API_URL:  "https://api.groq.com/openai/v1/chat/completions"
  AIAGENT_MODEL:    "llama-3.3-70b-versatile"

Plus a fourth, set from the developer's host environment so the
key never goes into the repo:

  AIAGENT_API_KEY: ${AIAGENT_API_KEY}

The developer sets AIAGENT_API_KEY in their local .env (gitignored)
or the shell. Production deploys use whatever secret-manager the
host platform provides.

## PART 14 — NETWORK / SECURITY NOTES

14.1 Egress

The Tomcat containers must be able to reach api.groq.com over
HTTPS (port 443). docker-compose's default bridge networking is
sufficient on most dev machines. Production deploys behind an
egress firewall need to allow:

  api.groq.com:443              (v1, Groq adapter)
  generativelanguage.googleapis.com:443  (PART 18.5, Gemini)
  ollama:11434                  (PART 18.5, Ollama - same compose)

14.2 API key handling

  - Stored ONLY in the Tomcat process env (set by docker-compose).
  - Read once at LlmClient static-init.
  - Never logged. [Domatar](../Domatar.md)'s existing "do not log Pwd /
    Token" rule extends to AIAGENT_API_KEY.
  - Never returned in any response body.

14.3 PII

The user's prompts MAY contain PII; the LLM provider's privacy
policy applies to whatever text is sent. Document this in the
"+ New chat" UI: a one-liner "Conversations are sent to <provider>
for completion. Don't share secrets." If a future requirement
demands provider-side data residency, the OllamaAdapter (PART
18.5) makes the data never leave the prv.

In the destination state, tool-call invocations also send tool
arguments and tool replies through the LLM. The same PII
considerations apply to that traffic; the chat UI's tool-trace
rendering (PART 6.6) makes it visible to the user what was sent.

## PART 15 — THE AGENT LOOP

This part specifies the loop in the abstract - the algorithm
that connects the user's request, the LLM, the tool catalogue,
and the platform's dispatcher. PART 7 specifies the Wui actions
that drive it; PART 8 specifies the obj-handler operations the
loop calls; PART 9 specifies the LLM client interface it uses;
PART 16 specifies how the tool catalogue is built; PART 17
specifies the safety policy that gates it.

Implementation status: PART 15 is the destination behaviour.
Today's doSendMessage (PART 8.2) is a one-shot LLM call with no
tools and no loop; it implements the chat case (Mode="Chat" in
the language of this section). Bringing PART 15 online for
Mode="ReadOnly" and Mode="Agent" is the work of PART 18.1 / 18.3.

15.1 Modes

A conversation has a Mode attr (PART 5.2) that determines what
the agent is allowed to do on the user's behalf:

  Chat       The LLM is asked to answer from its training data
             only. No tools are sent. (This is today's behaviour.)

  ReadOnly   The fixed read tools (GetObj, GetLnks, GetCls,
             GetHsts) plus a Read-restricted SendMsg (PART 16.1,
             16.6). The LLM may navigate the user's object graph and
             invoke any operation classified as Read on objects the
             calling user is authorised to read. No writes.

  Agent      The full fixed toolset, including SendMsg for Write and
             Destructive operations. Read operations execute without
             prompting. Write operations require user confirmation
             (PART 17.3) unless the conversation pre-authorises a
             specific (ClsAppId.ClsId.Operation) combination;
             Destructive operations always prompt.

The default Mode for a new conversation is Chat. The user may
change a conversation's Mode at any time from the chat-pane
toolbar (PART 6.5).

15.2 Per-turn algorithm

For each user turn:

  1. Append the user message to the conversation (the existing
     step 1 of doSendMessage; PART 8.2).

  2. Decide the working toolset:
       - If Mode == Chat: no tools.
       - Otherwise: the base toolset {listApps, useApp} (PART 16.2)
         plus any dynamic tools registered by prior useApp calls in
         this turn (PART 16.3), narrowed by Mode (PART 16.8).
         Dynamic tools are the typed operations of whichever app the
         LLM most recently loaded; they are replaced (not
         accumulated) on each useApp call.

  3. Build the LLM request: the Domatar primer + the user's
     system prompt (PART 16.2) + conversation history (with prior
     Role="tool" messages inline as messages) + the working
     toolset as the "tools" parameter.

  4. Call the LLM (PART 9). Reply is one of:

       a. A natural-language assistant message
          (finish_reason="stop") -> append it as Role="assistant",
          return all turns produced this round to the client,
          end of step.

       b. A tool_call (finish_reason="tool_calls") naming one or
          more (toolName, parameters) pairs.

  5. For each requested tool call:

       a. Resolve the call to (ClsAppId, ClsId, Operation,
          dstDomId). For the fixed read tools the operation is the
          tool itself (GetObj/Open/GetCls/ListHsts); for SendMsg the
          operation is the Operation argument and the class is the
          ClsAppId / ClsId arguments. The dstDomId comes from the
          "_target" parameter (PART 16.5).

       b. Classify the operation's SideEffect (PART 16.6: Read for
          the fixed read tools; descriptor lookup for SendMsg) and
          apply the safety policy (PART 17):
            - Read operations execute immediately.
            - Write operations that are in the conversation's
              PreApprovedWrites execute immediately.
            - Other Write tools queue a confirmation request
              for the browser; the loop suspends. The user sees
              the proposed call (PART 6.6) and clicks Approve /
              Reject (PART 7).
            - Destructive tools ALWAYS queue a confirmation
              request, even if listed in PreApprovedWrites.
              This is a hard safety gate; "Approve always"
              cannot bypass it.

       c. On execute: msgClient.send(dstDomId, msg) with the
          calling user's verified Context. Receive the reply.

       d. Append a Role="tool" message to the conversation
          (PART 5.3) with ToolName, ToolTargetSov, ToolArgs,
          ToolResult, FinishReason.

  6. After all tool calls in this batch resolve, loop back to
     step 3 with the augmented history. The LLM sees the tool
     outputs and either calls more tools or produces a
     natural-language answer.

15.3 Termination and budgets

  - Hard iteration cap: ITERATIONS_PER_TURN (default 8). When
    reached, the agent stops the loop and asks the LLM for a
    final summary using the partial history.
  - Token / cost budget: per-conversation TokensInPerTurn,
    TokensOutPerTurn (PART 17.1). Enforced before each LLM call.
  - Wall-clock deadline: per-turn DEADLINE_MS_PER_TURN (default
    60000ms). Enforced before each LLM call and before each tool
    dispatch.
  - User stop: a Stop button on the chat pane cancels the current
    turn; in-flight tool calls finish but no new tool calls are
    issued.

If a budget or cap is hit, the agent appends a Role="tool"
message with FinishReason indicating the cause, prompts the LLM
once more for a final summary, and returns. The loop never spins
indefinitely.

15.4 Audit trail

Every tool invocation is a Role="tool" msg row, in the same conv
as the user and assistant messages. The Navigator therefore shows
the full chain ([Navigator](Navigator.md)). The chat UI renders tool
messages as a collapsed "agent action" bar by default, expandable
to the full call (PART 6.6).

This makes the agent's behaviour fully inspectable after the
fact: "why did the assistant know X?" is answered by walking the
conv's msg children and seeing every tool call, its arguments,
and its reply.

The audit trail is not a security boundary - it is a UX feature.
Authorization is enforced at the destination (PART 12.3). The
audit is for the user's confidence and for offline review.

## PART 16 — THE ADAPTIVE TOOLSET AND THE DOMATAR PRIMER

The agent exposes two fixed base tools (listApps, useApp) at the
start of every non-Chat turn. When the LLM calls useApp(name), the
agent fetches the named app's class descriptor and registers each of
its Msgs as a TYPED LLM tool for the remainder of the turn. The LLM
then calls those typed tools directly by name - no Operation string
to construct or remember.

Rationale. Two fixed base tools plus per-app typed tools registered
on demand. Constant base cost with typed-schema enforcement: the LLM
cannot call a hallucinated operation name because hallucinated names
are never in the tool list. Token cost = 2 base tools + ~5-8 dynamic
tools for the current app — always small and always on-topic.

Key invariants:
  - The base toolset {listApps, useApp} is constant and never
    changes during a turn.
  - Dynamic tools are REPLACED (not accumulated) on each useApp
    call. At most one app's operations are active at a time. For
    a second app the LLM calls useApp again; the first app's tools
    are cleared and the second app's tools take their place.
  - Dynamic tool names ARE the operation names (e.g. GetSpreadsheet).
    The dispatcher resolves the target DomId from the per-turn
    DynamicToolRegistry; the LLM never writes DomId fields.
  - Every dynamic tool carries its SideEffect from the descriptor.
    ReadOnly mode blocks Write/Destructive dynamic tools exactly
    as it blocks Write tools.

16.1 The base toolset

Two tools are always present on non-Chat turns:

  listApps()
      Return the user's installed apps: for each, its ObjName
      (display name) and ObjDesc (one-line description). The LLM
      uses the ObjName to choose which app to pass to useApp.
      Internally: GetLnks on the caller's Navigator root, returning
      the subset of child links whose ClsId is "app".
      SideEffect: Read.
      listApps is OPTIONAL: if the user's question makes the target
      app obvious the LLM MAY call useApp directly and fall back
      to listApps only if useApp reports an unknown app name.

  useApp(name)
      Load the named app's operations as typed LLM tools. Steps:
        1. Match name case-insensitively against installed apps
           (discovered via GetLnks on the Navigator root).
        2. If no match: return an error that lists valid app names.
           The LLM can then call listApps or retry with a correct
           name; it need not call useApp again blindly.
        3. Fetch the class descriptor via GetCls on the app
           entry-point object (ClsId="app").
        4. For each Msg in the descriptor, build a typed LlmTool
           (PART 16.3) and record it in the DynamicToolRegistry
           (PART 16.3) keyed by Msg Name.
        5. CLEAR any dynamic tools registered by a prior useApp
           call in this turn, then register the new set.
        6. Return a summary listing the newly available tool names
           and one-line descriptions, so the LLM knows exactly
           what it can now call.
      SideEffect: Read.

16.3 Dynamic tool registration (DynamicToolRegistry)

The DynamicToolRegistry is a per-turn, in-memory map:

  toolName -> { targetDomId, operation, sideEffect }

  targetDomId   The DomId of the app entry-point object (the same
                object GetCls was called on). The dispatcher uses
                this as the message destination.
  operation     The Msg Name (= toolName). Stored for clarity /
                auditing; always equals toolName for dynamic tools.
  sideEffect    The Msg's SideEffect field (PART 16.5), defaulting
                to "Write" per the graceful-degradation rule (PART
                16.6). Stored so the safety layer can classify any
                dynamic call in O(1) without a second GetCls fetch.

When ToolDispatcher.execute() receives a tool call whose name is
in the DynamicToolRegistry, it dispatches a Domatar message to
targetDomId with operation=toolName and the LLM's arguments as
the body — the same send/parse tail as other handler calls.

Each dynamic tool's LlmTool entry is built by MsgsSchemaBuilder:

  name          The Msg's Name field.
  description   The Msg's Description field (fallback: Name).
  parameters    A JSON Schema object with one string property per
                Msg Parm; required[] lists non-optional parms. See
                PART 16.7 for the schema template.
  sideEffect    The Msg's SideEffect (stored; not part of the JSON
                schema sent to the LLM).

MsgsSchemaBuilder handles two descriptor formats:
  Structured:   "Msgs" is a JsonList of objects each with Name,
                Description, SideEffect, and Parms sub-fields.
  Plain-text:   "Msgs" is a string "Op(Arg1,Arg2)->{}; Op2(Arg3)->{}"
                and "SideEffect" is "Op:None, Op2:Write, ...".
                Parm names are parsed from the parenthesised
                signature; all parms are typed as "string".

16.4 The Domatar primer

The system-prompt primer for non-Chat turns is three sentences:

  "You have two base tools: listApps() shows available apps;
  useApp(name) loads that app's tools as callable functions.
  Call the loaded function directly to answer the question.
  For a second app, call useApp again — this replaces the
  previous app's tools."
  "Your identity: actId = <actId>."

The primer is assembled at turn time; improving it improves every
conversation at once. The user's per-conversation SystemPrompt is
appended after the primer and never replaces it.

16.5 Descriptor fields the agent reads at runtime

The agent reads the following fields from each (domatar, cls)
instance via GetCls (invoked internally by useApp). Fields marked
NEW are extensions to [Class](../platform/Class.md) PART 3; their grammar is
specified in [Class](../platform/Class.md) and summarised here.

  Class-level
  -----------
    ObjDesc                  Existing one-line summary.
    Description    [NEW]     Multi-line natural-language summary
                             of what the class IS and what it is
                             FOR.
    Conventions    [NEW]     How instances are typically named,
                             identified, owned, and laid out.
                             Audience: the agent and other tools.

  Per Msg
  -------
    Name           Existing.
    Type           Existing (return value type).
    Parms          Existing (input parameters).
    Description    [NEW]     What the operation does; when to
                             call it; when NOT to. Becomes the
                             typed tool's description.
    SideEffect     [NEW]     "Read" | "Write" | "Destructive".
    Auth           [NEW]     "Public" | "Verified" | "Owner" | ...

  Per Parm
  --------
    Name           Existing.
    Type           Existing.
    Description    [NEW]     What to supply for this parameter.
                             Becomes the schema property description.

16.6 Graceful degradation

  - Description missing (any layer) -> use the most informative
    available substitute (ObjDesc at class level; Name at Msg /
    Parm level).
  - SideEffect missing -> default to "Write". The dynamic tool is
    still registered and callable; in ReadOnly mode it is blocked
    at dispatch with a clear error.
  - Auth missing -> default to "Verified". The destination's
    hasRights() decides on dispatch as always.

16.7 Tool schemas

Base tool schemas:

  listApps:
  { "name": "listApps",
    "description": "List the apps available to the current user.",
    "parameters": { "type": "object", "properties": {}, "required": [] }
  }

  useApp:
  { "name": "useApp",
    "description": "Load an app's operations as callable tools.
                    Call this before using any app-specific tool.
                    Returns the list of loaded tool names.",
    "parameters": {
      "type": "object",
      "properties": {
        "name": { "type": "string",
                  "description": "App name as returned by listApps." }
      },
      "required": ["name"]
    }
  }

Dynamic tool schema template (one per Msg, generated by
MsgsSchemaBuilder):

  {
    "name"        : "<MsgName>",
    "description" : "<Msg Description, or Name if absent>",
    "parameters"  : {
      "type"       : "object",
      "properties" : {
        "<ParmName>" : { "type": "string",
                         "description": "<Parm Description or Name>" },
        ...
      },
      "required" : [ "<non-optional parms>" ]
    }
  }

Note: dynamic tools do NOT include a "_target" parameter. The
dispatcher resolves the target from the DynamicToolRegistry
automatically; the LLM only supplies the operation's own arguments.

16.8 Mode filtering and SideEffect classification

  Mode=Chat      -> no tools.
  Mode=ReadOnly  -> base tools plus dynamic tools whose SideEffect
                    is "Read". Dynamic tools with SideEffect "Write"
                    or "Destructive" are registered and visible in
                    the tool list but REFUSED at dispatch with an
                    informative error (not silently dropped, so the
                    LLM understands why).
  Mode=Agent     -> base tools plus all dynamic tools. Write tools
                    follow the consent gate (PART 17.3); Destructive
                    tools always prompt.

  listApps and useApp: always Read, regardless of Mode.

  SideEffect lookup for dynamic tools: O(1) from the
  DynamicToolRegistry. No second GetCls call at dispatch time.

  Error messages. When a dynamic tool is refused in ReadOnly mode,
  the error text names the tool, its SideEffect, and how to proceed.
  When a tool name is not in the registry and not a base tool, the
  error text says the operation does not exist and lists valid names.

16.9 SendMsg fallback

SendMsg may be offered in Agent mode for apps not yet registered via
useApp, and for cross-user reach (PART 17.5). It is NOT offered in
ReadOnly mode. SideEffect classification uses SideEffectClassifier
and the descriptor cache.

## PART 17 — SCOPING, SAFETY, AND USER CONSENT

The point of the safety layer is that the user is always in
control of what the agent does on their behalf and what it sees
on others' behalf. Per PART 12.3 the platform guarantees that
the agent cannot exceed the user's permissions; PART 17 adds
controls that let the user keep the agent inside a tighter box
than that ceiling for any given conversation.

Implementation status: PART 17 is the destination behaviour.
v1's conv has no Policy; the loop is Mode="Chat" with no
filters or confirmations.

17.1 Per-conversation policy

Each conv obj carries a Policy attr (a JSON document; PART 5.2):

  {
    "Mode"           : "Chat" | "ReadOnly" | "Agent",
    "AllowApps"      : [ "<appId>", ... ],     // empty = all
    "DenyApps"       : [ "<appId>", ... ],
    "AllowClasses"   : [ "<ClsAppId>.<ClsId>", ... ],
    "DenyClasses"    : [ "<ClsAppId>.<ClsId>", ... ],
    "PreApprovedWrites" : [ "<ClsAppId>.<ClsId>.<Msg>", ... ],
    "ForeignActIds"  : [ "<actId>", ... ],
    "Budgets" : {
      "IterationsPerTurn"  : 8,
      "TokensInPerTurn"    : 50000,
      "TokensOutPerTurn"   : 8000,
      "DeadlineMsPerTurn"  : 60000
    }
  }

The policy is the single source of truth for what the agent is
allowed to do in this conversation. The user edits it through
the conversation's Settings panel (PART 6.5).

The Mode field is duplicated as a top-level conv attr (PART 5.2)
for cheap selector-pane labelling; the Policy.Mode is
authoritative.

17.2 Read-only by default

The default Mode for a new conversation is Chat. The first time
the user enables tools, the chat UI offers ReadOnly first;
Agent (write-capable) requires a separate, explicit click and a
one-time explanation that lays out:

  - The agent will be able to call any operation classified as
    Write or Destructive on objects the user owns.
  - Write operations will pause the loop and wait for explicit
    approval, unless the user pre-approves them.
  - Destructive operations always pause and wait for explicit
    approval; they cannot be pre-approved.
  - The user can revoke or change Mode at any time.

This staged escalation makes the safer mode the default the user
falls into.

17.3 Write and Destructive confirmation

In Agent mode, the confirmation rule keys off the operation's
SideEffect. With the minimal toolset (PART 16) the fixed read tools
are Read by construction, and a SendMsg call's SideEffect is
classified at dispatch time from the target class's descriptor
(PART 16.6); an unknown or missing SideEffect is treated as Write.
Given that classification:

  - Read operations: execute immediately, no prompt.
  - Write operations in PreApprovedWrites: execute immediately.
  - Write operations NOT in PreApprovedWrites: prompt required.
  - Destructive operations: ALWAYS prompt, even if listed in
    PreApprovedWrites.  "Approve always" cannot bypass this
    gate.  This is a hard safety rule — operations that
    permanently delete or irreversibly modify data must never
    run silently.

When a prompt is required:

  1. The agent suspends the turn (no tool dispatch yet). It
     persists the pending tool call as a Role="tool" msg row
     with FinishReason="pending_user_approval".
  2. The browser shows a card (PART 6.6): tool name, target
     DomId, parameters, [Approve once] [Approve always] [Reject].
     For Destructive tools, [Approve always] is still shown
     but has no effect on future turns (the tool will always
     prompt again).
  3. On Approve once: the agent runs the tool, the loop resumes.
  4. On Approve always (Write only): the agent runs the tool AND
     adds the (ClsAppId.ClsId.Operation) to the conversation's
     PreApprovedWrites. (Conversation-scoped only; never a
     network-wide pre-approval.)
  5. On Reject: the agent updates the pending msg to
     FinishReason="rejected_by_user" and resumes the loop with
     a tool reply that says "user rejected"; the LLM may try a
     different approach or give up.

The state of "pending approval" lives on the persisted msg row,
not in memory, so a browser reload does not lose the queue: the
user comes back to the chat and the pending card is still there.

17.4 Allow / deny

AllowApps / DenyApps cap the catalogue at app granularity.
AllowClasses / DenyClasses are finer. The denylist always wins
over the allowlist. An empty AllowApps means "all installed apps
are eligible"; an empty AllowClasses means "all classes within
eligible apps are eligible".

The lists are evaluated at catalogue-assembly time; tools
disallowed by them never reach the LLM at all.

17.5 Foreign user access

The conversation's ForeignActIds list authorises the agent to
include other users' classes in the catalogue. To consult
dave@quippin's data, the user adds "dave@quippin" to
ForeignActIds. This does not grant any new permission - dave's
prv still runs his hasRights for every call - but it tells the
agent which foreign actIds to walk during catalogue assembly
(PART 16.1) and DomId resolution.

Without an entry, the agent will not navigate to foreign hosts.
With an entry, the foreign user's public-facing classes are
reachable; non-public operations are classified by Auth (PART 16.6)
and rejected at dispatch by the foreign prv's hasRights.

The foreign-actId list is not a security boundary; it is a
relevance filter. The actual authority decision happens at the
foreign prv (PART 10).

17.6 Budgets

Budgets cap per-turn cost in three dimensions: iterations,
tokens, and wall-clock. The agent enforces them before each
LLM call and before each tool dispatch (PART 15.3). Exceeding
any budget ends the turn with a final summary attempt.

The budgets are intended as runaway-prevention, not pricing.
Per-conversation cost accounting (TokensIn / TokensOut on the
conv obj) plus per-account aggregation are existing data;
turning them into a hard quota is part of the same picture as
per-user API keys (PART 18.5).

## PART 18 — DEFERRED WORK

The work groups below describe the progression from the
implemented v1 chat foundation to the destination state of
## PART 1 — They are coherent areas of work that a separate task
document can decompose into commits; they are NOT a prescriptive
task list and they are NOT phase-ordered, although natural
dependencies between them are noted.

Each group says what is unbuilt, where in this spec the target
behaviour is described, and what the prerequisite work is.

18.1 Tool-calling baseline

  Bring the agent loop (PART 15) and the tool catalogue
  assembly (PART 16) online for the user's own classes and
  read-only operations. End state: a user with Mode=ReadOnly
  can ask the agent questions whose answers come from their own
  Domatar object graph.

  Touches: ConvImpl.doSendMessage (extended to the loop shape),
  ConversationsImpl (Mode-aware), AgentWui (no new actions for
  this slice), LlmClient + GroqAdapter (tool-call request /
  response shape; PART 9.1, 9.3), the conv data model (Mode,
  Policy attrs; PART 5.2), the chat UI (Mode toggle, tool-trace
  rendering; PART 6.5, 6.6).

  Depends on: 18.4 (descriptor enrichment) for tool quality;
  partially functional without it (PART 16.3 fallbacks).

18.2 Cross-user / cross-prv reach

  Bring up Mode=Agent + ForeignActIds + the discovery flows of
  PART 10.5. Walk follow lists / shared-with / Quippin Directory
  through tool calls; assemble catalogues that include foreign
  classes.

  Touches: catalogue assembly (PART 16.1 cross-user branch),
  the ForeignActIds policy field, the Settings UI for managing
  it.

  Depends on: 18.1. Substrate (the dispatch and re-verification
  paths of PART 10) is fully implemented.

18.3 Writes and consent

  Implement the user-confirmation card (PART 17.3) and the
  PreApprovedWrites mechanism. Wire the chat UI's Settings panel.

  Touches: AgentWui (ApproveToolCall / RejectToolCall actions;
  PART 7), ConvImpl (suspend / resume of the loop across
  approval), the chat UI (confirmation card, Settings panel),
  the conv Policy attr.

  Depends on: 18.1.

18.4 Class descriptor enrichment

  Add Description (per-class, per-Msg, per-Parm), Conventions,
  SideEffect, and Auth fields to [Class](../platform/Class.md) PART 3, and
  populate them in every app's install routine. Without these
  fields, agent tool selection is materially less reliable;
  with them it is mechanical.

  This work lives mostly in [Class](../platform/Class.md) and per-app install
  routines, NOT in the AI Agent app. The AI Agent only consumes
  the fields. Each app can be enriched independently; partial
  enrichment is useful (the agent works better on the apps that
  have been enriched and falls back on the rest, PART 16.3).

  Also depends on [Class](../platform/Class.md) PART 9 (the GetCls operation):
  agents need to query schemas at runtime, not only at install
  time.

  Depends on: nothing at the AI Agent layer; this is a
  cross-spec / cross-app effort that the AI Agent benefits from.

18.5 LLM-side enhancements

  - Streaming responses (server-sent events; tokens appear as
    they arrive). UX matters more here once tool calls extend
    turn duration.
  - Ollama adapter (local LLM, no egress, no quota).
  - Gemini adapter.
  - Per-user API keys (per-user-paid quota; encrypted-at-rest;
    same JCE wrapping accounts need).
  - Rate-limit handling with backoff on 429.
  - Markdown rendering on the chat pane.
  - Per-turn cost accounting / quotas surfacing in the Settings
    panel.

  Depends on: 18.1 only for the streaming/tool-call interplay;
  the rest are independent.

18.6 UX additions

  - User-facing model picker per conversation. Today
    AIAGENT_MODEL is set per Tomcat; v2 surfaces a per-
    conversation model selector in the UI, persisted on
    conv.attrs.Model.
  - Per-msg ops (Edit, Regenerate, Delete-just-this-msg) on
    MsgImpl. v1 ships MsgImpl as a pure authorization gate.
  - Pagination on GetConversation / ListConversations (today
    MaxN=1000 caps both).
  - Conversation export (JSON or Markdown).
  - Conversation forking ("Branch from here").
  - Per-conversation system prompt editor.
  - Search across conversations.

  Depends on: nothing; these are independent quality-of-life
  improvements.

18.7 Sharing

  - Owner can write a lnk <conv> -> <other user's nav root>
    with a "shared-with" tag and an explicit ACL row. The other
    user's Navigator picks it up under "Shared with me". Reuses
    the cross-prv drill-through pattern from
    [Navigator](Navigator.md) PART 5.4.
  - Foreign-prv GetConversation lands cross-prv at the second
    user's home prv and the existing verifyAndStamp gate
    authorises it.

  Depends on: nothing; the cross-prv plumbing is already
  exercised by every other cross-prv operation in the system.

18.8 Long-term

  These are extensions of the destination state, not the
  destination state itself. They are pushed off until the
  destination is reached and stable.

  - Ambient agency: the agent runs without a human in the loop
    on a schedule (cron-like) or in response to events (a quip
    from a followed user, an inbox arrival). Same loop,
    different trigger.
  - Multi-agent: two users' agents talk on their behalf.
    Already expressible: each agent acts as its user, the
    dispatch is cross-prv, and authorization is per-object as
    always. No new substrate; new UX and policy.
  - Agent-to-agent protocol negotiation: when an agent
    encounters an undescribed class, it can ask the owning
    class's GetCls descriptor for richer documentation - itself
    a tool call - and may even discover new fields the LLM uses
    to bootstrap understanding. The class descriptor becomes a
    living, queryable contract.
  - Cross-agent reasoning chains: an agent cites another
    agent's tool-call audit (which is a normal Domatar object
    graph) as part of its own reasoning, with the user's
    explicit consent.

18.9 Adaptive toolset (dynamic tool registration)

  See PART 16. listApps / useApp / per-app typed tools are the
  ReadOnly-mode surface. Coarse-grained LLM-native operations on
  individual apps (e.g. GetSpreadsheet) answer common questions in
  a single call rather than requiring the LLM to navigate to child
  objects.

## PART 19 — SOURCE LAYOUT

Files (existing v1):

  src/main/webapp/aiagent.html
      The two-pane chat page (PART 6).

  src/main/webapp/icons/aiagent.svg
      The AI Agent's icon for Desktop (PART 13.1).

  src/main/webapp/icons/cls/aiagent/conversations.svg
  src/main/webapp/icons/cls/aiagent/conv.svg
  src/main/webapp/icons/cls/aiagent/msg.svg
      Navigator class icons (16x16) for the agent's tree nodes.

  src/main/java/com/aiagent/webui/AgentWui.java
      Wui servlet (PART 7).

  src/main/java/com/aiagent/objimpl/ConversationsImpl.java
      Container handler (PART 8.1).

  src/main/java/com/aiagent/objimpl/ConvImpl.java
      Per-conversation handler (PART 8.2).

  src/main/java/com/aiagent/objimpl/MsgImpl.java
      Per-message handler (PART 8.3). Subclass of ObjImpl with an
      owner-match hasRights override; no operations of its own.

  src/main/java/com/aiagent/llm/LlmClient.java
  src/main/java/com/aiagent/llm/LlmMessage.java
  src/main/java/com/aiagent/llm/LlmResult.java
  src/main/java/com/aiagent/llm/GroqAdapter.java
      LLM client (PART 9).

  src/main/java/com/aiagent/install/AiagentInstall.java
      Install routine (PART 11.1).

  mySQL/dump-2024-01-20-aiagent.sql
      Legacy backfill (PART 11.3).

Edits to existing files (already in v1):

  src/main/java/com/domatar/core/ImplMap.java
      Register (aiagent, conversations) -> ConversationsImpl,
      (aiagent, conv) -> ConvImpl, and (aiagent, msg) -> MsgImpl.

  src/main/java/com/domatar/act/ActManagerImpl.java
      Append AiagentInstall.install(...) to the install chain
      in addAct's isRoot branch (PART 11.2).

  src/main/java/com/desktop/objimpl/AppsImpl.java
      seedDefaultCatalog() grows a fourth row for app-aiagent
      (PART 13.1).

  src/main/webapp/WEB-INF/web.xml
      <servlet>/<servlet-mapping> entries for /aiagent ->
      aiagent.html, mirroring /desktop and /navigator.

  docker-compose.yml
      Add "aiagent" to tomcat1's domatar_net aliases, plus four
      new env vars on both tomcats (PART 13.3).

Destination-state additions (PART 18.1, 18.3):

  src/main/java/com/aiagent/llm/LlmTool.java
  src/main/java/com/aiagent/llm/LlmToolCall.java
      Tool-call types for the extended LlmClient interface
      (PART 9.1).

  src/main/java/com/aiagent/agent/Catalogue.java
      Class-descriptor walker that produces an LlmTool[] from
      the user's installed-app graph (PART 16).

  src/main/java/com/aiagent/agent/Policy.java
      Per-conversation policy parsing and evaluation (PART 17).

  src/main/java/com/aiagent/agent/AgentLoop.java
      The loop algorithm of PART 15. Called from
      ConvImpl.doSendMessage when conv.Mode != "Chat".

  src/main/java/com/aiagent/objimpl/ConvImpl.java   (extended)
      SetMode, SetPolicy, ApproveToolCall, RejectToolCall ops;
      doSendMessage delegates to AgentLoop in non-Chat modes.

  src/main/java/com/aiagent/webui/AgentWui.java     (extended)
      SetMode, SetPolicy, ApproveToolCall, RejectToolCall
      Wui actions (PART 7).

  src/main/webapp/aiagent.html                      (extended)
      Mode selector, Settings panel, tool-trace rendering,
      confirmation card (PART 6.5, 6.6).

These additions are the surface area for the work groups in
## PART 18 — They are listed here for cross-reference; the actual
sequencing is in the separate task document.

## PART 20 — DISPATCH TRACE

20.1 Chat-only trace (current behaviour)

Dave (on prv1) clicks the "AI Agent" tile on his Desktop, which
opens /quippin/aiagent in a new tab.

  1. Browser GETs /quippin/aiagent -> aiagent.html.

  2. aiagent.html on load:
       POST /quippin/AgentWui  { Action=ListConversations }

  3. AgentWui (running on tomcat1 / prv1):
       - DomatarServlet outer dispatch verifies Dave's session.
       - Builds:
           srcDomId = (prv1, aiagent, dave@quippin, AgentWui)
           dstDomId = (aiagent-dave@quippin, aiagent,
                       dave@quippin, conversations)
           clsId    = (aiagent, conversations)
       - msgClient.send(dstDomId, msg).

  4. HttpClient.dispatch:
       - dst.hstId = "aiagent-dave@quippin", != DOMATAR_HSTID,
         self-dispatch shortcut does not fire.
       - Directory hit: PrvId=prv1, Domain=tomcat1:8080.
       - PrvId == DOMATAR_HSTID, sendLocal path.

  5. ImplMap (aiagent, conversations) -> ConversationsImpl.
     ConversationsImpl.handleMsg routes ListConversations to
     getConversations(...).
     First-time call: prefix scan returns []. Reply:
       { Conversations: [] }.

  6. Browser renders an empty left pane and a "Start a new chat"
     placeholder in the right pane.

Dave clicks "+ New chat", types "Why is the sky blue?", clicks
Send:

  7. POST /quippin/AgentWui  { Action=SendMessage, ConvId="",
                               Text="Why is the sky blue?" }

  8. AgentWui sees empty ConvId -> dst is the conversations
     container.

  9. ConversationsImpl.SendMessage:
       - Generate convId = "conv-0OabcXyZ".
       - Build conv obj: Title="Why is the sky blue?",
         Model=AIAGENT_MODEL, SystemPrompt=default,
         Mode="Chat", ...
       - ObjDb.addObj(conv). LnkDb.addLnk(<conversations> ->
         <conv>).
       - doSendMessage:
           - Append user msg ("Why is the sky blue?").
           - history = [system, user].
           - LlmClient.complete(model, history) -> Groq POST,
             returns "Sunlight scatters in the atmosphere..."
           - Append assistant msg.
           - Update conv counters.
       - Reply:
           { ConvId: "conv-0OabcXyZ",
             Title:  "Why is the sky blue?",
             UserMessage:      { Role:"user",
                                 Text:"Why is the sky blue?",
                                 Time:..., ... },
             AssistantMessage: { Role:"assistant",
                                 Text:"Sunlight scatters...",
                                 Model:"llama-3.3-70b-versatile",
                                 TokensIn:12, TokensOut:42,
                                 FinishReason:"stop" } }

 10. Browser appends both messages on the right; adds the new
     "Why is the sky blue?" entry to the left pane and selects
     it.

The Navigator ([Navigator](Navigator.md)), opened in another tab, shows
the new conv-0OabcXyZ under
  navigator-dave@quippin.root
    -> app-aiagent
       -> conversations
          -> conv-0OabcXyZ
             -> msg-... (user)
             -> msg-... (assistant)
all picked up via the persistent lnks ConvImpl wrote at
SendMessage time. No Navigator-side changes are needed.

20.2 Agent-mode trace (destination state)

Same Dave, same conv, but conv.Mode has been set to "Agent" via
SetMode and conv.Policy.PreApprovedWrites contains
"bookstore.forsale.ListBook" via SetPolicy.

Dave types "I have an old copy of Dune in fair condition; list
it for $8":

  1. POST /quippin/AgentWui { Action=SendMessage,
                              ConvId="conv-0OabcXyZ",
                              Text="I have an old copy ..." }

  2. AgentWui dispatches SendMessage to (aiagent, conv) ->
     ConvImpl.SendMessage. conv.Mode = "Agent" -> doSendMessage
     delegates to AgentLoop.

  3. AgentLoop, step 1: append the user msg.

  4. AgentLoop, step 2: assemble the catalogue.
       - Walk Dave's app-* lnks. Find app-bookstore.
       - GetCls(bookstore.forsale) -> descriptor with ListBook
         (SideEffect=Write, Auth=Owner) and GetForSale
         (SideEffect=Read, Auth=Owner).
       - GetCls(bookstore.catalog) -> descriptor with
         GetCatalog (SideEffect=Read, Auth=Public).
       - Filter by Mode=Agent: keep all.
       - Filter by Auth: caller is Dave; ListBook on
         bookstore-dave@quippin passes Owner-match; GetForSale
         on the same passes; GetCatalog is Public, kept.
       - Project to LlmTool[]; pass to LlmClient.

  5. AgentLoop, step 3+4: LlmClient.complete returns
     finish_reason="tool_calls" with one call:
       bookstore.forsale.ListBook(
         _target = (bookstore-dave@quippin, bookstore,
                    dave@quippin, forsale),
         Title="Dune", Author="Frank Herbert", ISBN="...",
         Price="$8", Condition="Fair",
         Description="Old paperback")

  6. AgentLoop, step 5: ListBook is in PreApprovedWrites ->
     execute immediately.
       - msgClient.send(_target DomId, msg). Local;
         ForSaleImpl.ListBook runs hasRights -> Owner-match,
         passes. Writes the listing obj. Calls into the catalog,
         which dispatches cross-prv to bookstore's central host
         (the catalog lives there); HttpClient.sendHttp.
         Returns { ListingId: "listing-..." }.
       - Append a Role="tool" msg to the conv with ToolName=
         "bookstore.forsale.ListBook", ToolArgs=..., ToolResult=
         { ListingId: ... }.

  7. AgentLoop loops back to step 3. The LLM sees the tool reply
     and produces a natural-language assistant message:
       "I've listed your copy of Dune for $8. The listing is
        live on the bookstore catalog now."
     finish_reason="stop".

  8. AgentLoop, step 4a: append the assistant msg, end.

  9. ConvImpl.doSendMessage replies with all three turns: user,
     tool, assistant.

 10. Browser renders:
       - "user: I have an old copy ..."
       - "[tool] called bookstore.forsale.ListBook"  (collapsed)
       - "assistant: I've listed your copy ..."

The Navigator shows the same structure under
conv-0OabcXyZ - the tool msg is just another msg child - and
the ForSale and Catalog containers show the new listing. The
agent did exactly what Dave could have done by clicking through
ForSale himself; the platform never granted it any extra
authority.

## PART 21 — SMOKE-TEST GUIDE

This section walks v1 chat-only acceptance. Acceptance for the
destination-state agent behaviour is the corresponding section
of the task document; it builds on the same prerequisites.

21.1 Prerequisites

  1. A free Groq API key.
       https://console.groq.com/ -> "API Keys" -> "Create API key"
       Copy the key (starts with "gsk_...").

  2. Build and deploy
       mvn package
       docker compose restart tomcat1 tomcat2

  3. Set the API key in docker-compose.yml
       AIAGENT_API_KEY: "gsk_<your key here>"
     then restart both Tomcats:
       docker compose restart tomcat1 tomcat2

  4. For existing users (dave@quippin, micha@quippin): run the
     backfill script against both databases:

       # Open a MySQL shell into db1
       docker exec -i quippin-db1-1 \
           mysql -u root -pdomatar domatar \
           < mySQL/dump-2024-01-20-aiagent.sql

       # Repeat for db2
       docker exec -i quippin-db2-1 \
           mysql -u root -pdomatar domatar \
           < mySQL/dump-2024-01-20-aiagent.sql

     (The script is idempotent; safe to run more than once.)

  5. Add the AI Agent Desktop tile for existing seed users.
     seedDefaultCatalog handles this automatically for NEW
     sign-ups; for dave and micha already in the database run:

       docker exec -i quippin-db1-1 mysql -u root -pdomatar domatar <<'SQL'
       INSERT IGNORE INTO obj
         (HstId,AppId,ActId,ObjId,ClsAppId,ClsId,ObjName,ObjDesc,Attrs)
       VALUES
         ('desktop-dave@quippin','desktop','dave@quippin','app-aiagent',
          'desktop','app','AI Agent','Chat with an AI in your Domatar',
          JSON_OBJECT('DisplayName','AI Agent',
                      'IconPath','/quippin/icons/aiagent.svg',
                      'LaunchPath','/quippin/aiagent','Position','4')),
         ('desktop-micha@quippin','desktop','micha@quippin','app-aiagent',
          'desktop','app','AI Agent','Chat with an AI in your Domatar',
          JSON_OBJECT('DisplayName','AI Agent',
                      'IconPath','/quippin/icons/aiagent.svg',
                      'LaunchPath','/quippin/aiagent','Position','4'));
       INSERT IGNORE INTO lnk
         (SrcHstId,SrcAppId,SrcActId,SrcObjId,
          DstHstId,DstAppId,DstActId,DstObjId,
          ClsAppId,ClsId,ObjName,ObjDesc,TagAppId,Tag,SeqNum,Val)
       VALUES
         ('desktop-dave@quippin','desktop','dave@quippin','apps',
          'desktop-dave@quippin','desktop','dave@quippin','app-aiagent',
          'desktop','app','AI Agent','Chat with an AI in your Domatar',
          'desktop','app',4,''),
         ('desktop-micha@quippin','desktop','micha@quippin','apps',
          'desktop-micha@quippin','desktop','micha@quippin','app-aiagent',
          'desktop','app','AI Agent','Chat with an AI in your Domatar',
          'desktop','app',4,'');
       SQL

       # db2 stores micha's desktop too - run the same block:
       docker exec -i quippin-db2-1 mysql -u root -pdomatar domatar <<'SQL'
       INSERT IGNORE INTO obj ... (same block)
       INSERT IGNORE INTO lnk ... (same block)
       SQL

21.2 UI walkthrough

  Step 1 - Desktop tile
    Open http://prv1.local:8080/quippin/desktop.
    Confirm the "AI Agent" tile appears fourth (after Navigator).

  Step 2 - Empty state
    Click "AI Agent". The page /quippin/aiagent loads.
    Left pane: "No conversations yet."
    Right pane: "Type below to start a new conversation."

  Step 3 - First message (new conversation)
    Type "Hello" in the text box. Click Send (or press Enter).
    Expected:
      - A "user" message block appears immediately.
      - A "..." typing indicator appears.
      - Within a few seconds the assistant reply appears.
      - The left pane gains a new entry "Hello".
      - The right-pane title changes to "Hello".

  Step 4 - Follow-up message (existing conversation)
    Type a follow-up. Click Send.
    Expected: the assistant reply is context-aware (full history
    is sent to Groq).

  Step 5 - Rename
    Click Rename in the toolbar. Enter a new title.
    Expected: title updates in both the header and the left pane.

  Step 6 - New chat
    Click "+ New chat". Left pane: nothing highlighted.
    Type a message. Click Send.
    Expected: a second conversation appears in the left pane.

  Step 7 - Switch conversation
    Click the first conversation in the left pane.
    Expected: its messages are fetched and displayed.

  Step 8 - Navigator check
    Open http://prv1.local:8080/quippin/navigator in another tab.
    Expand: root -> app-aiagent -> conversations -> conv-... -> msg-...
    Both msg objects should be visible as children of the conv.

  Step 9 - Delete
    Select a conversation and click Delete. Confirm.
    Expected: removed from the left pane; next conversation
    selected (or empty state if none remain).

21.3 Database verification

  -- List aiagent hosts
  SELECT * FROM hst WHERE HstId LIKE 'aiagent-%';

  -- List conversations for dave
  SELECT ObjId, ObjName FROM obj
  WHERE HstId='aiagent-dave@quippin' AND ClsId='conv';

  -- List messages in a conversation (replace <conv-id>)
  SELECT ObjId,
         Attrs->>'$.Role'        AS Role,
         LEFT(Attrs->>'$.Text',60) AS Preview
  FROM obj
  WHERE HstId='aiagent-dave@quippin'
    AND ObjId LIKE 'msg-%'
  ORDER BY Attrs->>'$.Time';

  -- Check lnk chain: conversations -> conv
  SELECT SrcObjId, DstObjId, ClsId FROM lnk
  WHERE SrcHstId='aiagent-dave@quippin' AND SrcObjId='conversations';

21.4 Troubleshooting

  "API key missing" in assistant reply
    -> Set AIAGENT_API_KEY in docker-compose.yml and restart Tomcats.

  "LLM error: 429" in assistant reply
    -> Groq rate limit hit. Wait a moment and retry.

  "LLM error: 401" in assistant reply
    -> Invalid API key. Check the key on https://console.groq.com/.

  AI Agent tile missing on Desktop
    -> Run the desktop backfill SQL in 21.1 step 5.
    -> For new sign-ups seedDefaultCatalog runs automatically on
       first login - no backfill needed.

  "Hst not found" in Tomcat logs when opening /aiagent
    -> The aiagent backfill SQL (21.1 step 4) has not been run,
       or the 'aiagent' alias is missing from docker-compose.yml.
       Verify: SELECT * FROM hst WHERE HstId='aiagent-dave@quippin';

# END OF SPEC
