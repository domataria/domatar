package com.domatar.util;

import java.util.List;

import com.domatar.log.OpMsg;

/**
 * What a handler uses to call another object, and to ask what this
 * operation has already done.
 *
 * <p>The platform passes one of these into {@code handleMsg} and
 * {@code hasRights}. Application code does not construct it, and it
 * cannot start a fresh operation. {@link #send} continues the
 * operation this delivery already belongs to.
 *
 * <p>Three ids that are easy to mix up:
 * <ul>
 * <li><b>Account</b> ({@code actId}) — the person who is signed in.
 *     One person may have several live sessions at once.</li>
 * <li><b>Operation</b> ({@link #contextId()}) — one chain of calls
 *     that started together, such as one click and every object it
 *     then calls. Two sessions of the same person are two operations.
 *     A later undo is a new operation too.</li>
 * <li><b>This object</b> ({@link #getSrcId()}) — the object your
 *     handler is running as. A {@link #send} leaves from here.</li>
 * </ul>
 *
 * <p>"Have I been here?" is two different questions:
 * <ul>
 * <li>{@link #inOwnPath()} — does the path of <em>this request</em>
 *     already visit this object? That is a loop
 *     (A called B, and B called A). It is read from memory, not
 *     from the database. The platform refuses that loop unless the
 *     class overrides {@code rights()} to allow it.</li>
 * <li>{@link #alreadyEntered()} — has this operation already run
 *     <em>this message</em> on this object, before the delivery you
 *     are handling now? That comes from the visit log. The answer
 *     is fixed at the start of the delivery, so {@code hasRights}
 *     and {@code handleMsg} see the same value. It is not a lock:
 *     two calls that overlap can both see "no".</li>
 * </ul>
 *
 * <p>The query methods do not record a visit. {@link #attach} stores
 * a note on a visit that already exists.
 */
public interface DomatarMsgClient
{
  /**
   * Send {@code document} to {@code domId} and return the reply text.
   *
   * <p>This parses {@code document} and calls
   * {@link #send(DomId, JsonMsg)}. Build a {@link JsonMsg} instead
   * when you are assembling the message in code.
   *
   * @param domId who should receive the message. An empty host id
   *              means "on this host".
   * @param document the message as JSON text
   * @return the reply as text. A callee that refuses the call still
   *         returns a message; the refusal is an error inside that
   *         message.
   * @throws DomatarException {@code document} is not a message, the
   *         path would be too deep, or the destination cannot be
   *         reached
   */
  public String send (DomId domId, String document) throws DomatarException;

  /**
   * Send {@code jsonMsg} to {@code domId} as the next step of this
   * same operation, and return the reply.
   *
   * <p>You fill in the body: which operation to run, and its
   * attributes. The platform sets the sender to {@link #getSrcId()},
   * keeps {@link #contextId()}, and extends the path. A destination
   * on this host is called directly. A destination on another host
   * is an HTTP call to that host.
   *
   * <p>A callee that refuses returns an error message. This method
   * throws when the send itself cannot be made, for example because
   * another hop would pass the platform's depth limit, or because
   * the other host cannot be reached.
   *
   * @param domId destination. An empty host id means "on this host".
   * @param jsonMsg the message to send. Head fields you set are
   *                replaced for this hop.
   * @return the reply
   * @throws DomatarException the hop cannot be sent
   */
  public JsonMsg send (DomId domId, JsonMsg jsonMsg) throws DomatarException;

  /**
   * The object this handler is running as.
   *
   * <p>The name is "source" because {@link #send} leaves from this
   * object. It is the destination of the message you are handling,
   * not the caller who sent that message.
   *
   * @return this object's id
   */
  public DomId getSrcId();

  /**
   * Id of the operation this delivery belongs to.
   *
   * <p>Every {@link #send} from here keeps the same id. A second
   * click, another browser tab, or a later undo is a new id. This
   * is not the account id.
   *
   * @return the operation id, or {@code null} when this client has
   *         no platform context
   */
  public String contextId();

  /**
   * A copy of this client that carries a different session token.
   *
   * <p>The object, the operation, the path, and the account stay
   * the same. Only the login token changes. Use it when a nested
   * {@link #send} must present another session of the same account.
   * It does not sign the call in as a different person. This client
   * is left unchanged.
   *
   * @param token the session token to put on the copy
   * @return the new client
   * @throws DomatarException the copy cannot be built
   */
  public DomatarMsgClient withToken(String token) throws DomatarException;

  /**
   * The objects this operation passed through to reach you, in order.
   *
   * <p>The first id is whoever started the operation. Each id after
   * that is a destination, and the last id is this object
   * ({@link #getSrcId()}). A call that came straight here has two
   * ids: the starter, then you. An empty array means there is no
   * path.
   *
   * <p>Each call returns a new array. Changing the array does not
   * change the path that a later {@link #send} will extend.
   *
   * @return the path, possibly empty
   * @throws DomatarException an id on the path cannot be read
   */
  public DomId[] domIdPath() throws DomatarException;

  /**
   * How many times this operation had already run this message on
   * this object, before the delivery you are handling.
   *
   * <p>The first delivery is {@code 0}. The next delivery of the
   * same message is {@code 1}, and so on. A different message name
   * on the same object has its own count; use
   * {@link #priorVisitCountAny()} when you care about any message.
   *
   * <p>The number is taken at the start of this delivery and does
   * not change in {@code handleMsg}, even though the platform may
   * already have recorded this delivery by then. Do not treat a
   * stored count of {@code 1} as "I am first".
   *
   * @return arrivals of this message before this delivery
   * @throws DomatarException the visit log cannot be read
   * @see #alreadyEntered()
   */
  public int priorVisitCount() throws DomatarException;

  /**
   * {@link #priorVisitCount()} for an operation you name.
   *
   * <p>The message name is still the one this handler is running.
   * Pass the current {@link #contextId()} to ask about this
   * operation. Pass an older id, such as the operation a
   * {@code Compensate} is undoing, to ask about that earlier chain.
   * An unknown id, or {@code null}, is {@code 0}.
   *
   * @param contextId the operation to ask about
   * @return arrivals of this message on this object in that
   *         operation, before this delivery when the id is the
   *         current one
   * @throws DomatarException the visit log cannot be read
   */
  public int priorVisitCount(String contextId) throws DomatarException;

  /**
   * Whether this request's path already visits this object.
   *
   * <p>True when {@link #getSrcId()} appears as an earlier
   * destination on {@link #domIdPath()}. The hop that is arriving
   * now does not count, so the first time a call reaches this
   * object the answer is false. A path with no hops is false.
   *
   * <p>This is not {@link #alreadyEntered()}. A loop can be seen
   * here with no visit row, and a second visit of the same message
   * can happen with no loop. The platform denies a true result in
   * {@code rights()} unless the class overrides that method and
   * allows the call. Reading this method does not hit the database.
   *
   * @return {@code true} when this object is already a destination
   *         earlier on this request's path
   * @throws DomatarException the path cannot be read
   */
  public boolean inOwnPath() throws DomatarException;

  /**
   * Whether this operation had already run this message on this
   * object, before the delivery you are handling.
   *
   * <p>This is {@link #priorVisitCount()} {@code > 0}. False on the
   * first delivery of this message. True on the second. A different
   * message name is a different question: after {@code Ping}, a
   * first {@code Pong} is still false. Use
   * {@link #priorVisitCountAny()} to ask whether any message has
   * already reached this object.
   *
   * <p>The answer is fixed at the start of the delivery. It stays
   * the same in {@code handleMsg}. Two overlapping calls can both
   * see false; this method is not a lock, and it does not decide
   * whether the call is allowed.
   *
   * @return {@code true} when an earlier delivery of this message
   *         already arrived
   * @throws DomatarException the visit log cannot be read
   */
  public boolean alreadyEntered() throws DomatarException;

  /**
   * {@link #alreadyEntered()} for an operation you name.
   *
   * <p>The message name is still the one this handler is running.
   * Use the current {@link #contextId()} for this operation, or an
   * older id when you are looking at the operation a
   * {@code Compensate} undoes.
   *
   * @param contextId the operation to ask about
   * @return {@code true} when that operation had already run this
   *         message on this object
   * @throws DomatarException the visit log cannot be read
   */
  public boolean alreadyEntered(String contextId) throws DomatarException;

  /**
   * How many visits this object already had in this operation,
   * adding every message name, before the delivery you are handling.
   *
   * <p>Use this when the question is "has this operation touched
   * this object at all?", not "have I already run this exact
   * message?". After a {@code Ping}, a first {@code Pong} still has
   * {@link #alreadyEntered()} false and this count greater than
   * zero.
   *
   * @return earlier visits of any message, or {@code 0} when this
   *         object is new to the operation
   * @throws DomatarException the visit log cannot be read
   */
  public int priorVisitCountAny() throws DomatarException;

  /**
   * {@link #priorVisitCountAny()} for an operation you name.
   *
   * <p>{@code null} or an unknown id is {@code 0}. Unlike
   * {@link #priorVisitCount(String)}, this count is not limited to
   * the message you are handling.
   *
   * @param contextId the operation to ask about
   * @return earlier visits of any message on this object in that
   *         operation
   * @throws DomatarException the visit log cannot be read
   */
  public int priorVisitCountAny(String contextId) throws DomatarException;

  /**
   * Messages this object has already sent during this operation,
   * oldest first.
   *
   * <p>Each {@link OpMsg} names one earlier {@link #send}: who it
   * went to ({@code dstDomId}) and which message ({@code outMsgName}).
   * The list is empty when this object has not sent anything yet.
   * Undo walks the list from the end back to the start.
   *
   * @return this object's outgoing sends, possibly empty
   * @throws DomatarException the visit log cannot be read
   */
  public List<OpMsg> outMsgs() throws DomatarException;

  /**
   * {@link #outMsgs()} for an operation you name.
   *
   * <p>{@code null} returns an empty list.
   *
   * @param contextId the operation whose sends you want
   * @return that operation's outgoing sends from this object,
   *         oldest first, or an empty list
   * @throws DomatarException the visit log cannot be read
   */
  public List<OpMsg> outMsgs(String contextId) throws DomatarException;

  /**
   * Store a JSON note on this delivery's visit.
   *
   * <p>The note is kept under {@code slot} on the visit for this
   * operation, this object, and the message you are handling.
   * {@code json} may be a {@link JsonMap}, {@link JsonList},
   * {@link String}, {@link Number}, or {@link Boolean}.
   * {@code null} deletes the note. A {@code byte[]} or any other
   * type is ignored. {@code attachExpiresAt} is when the note may
   * be dropped, as milliseconds since the epoch.
   *
   * <p>{@code slot} is a name you choose: a letter, then letters,
   * digits, or underscores. Platform names such as {@code saga} and
   * {@code payment} are reserved; writing them does nothing.
   * Calling this during {@code Compensate} records the note on the
   * Compensate visit, not on the message you are undoing. Use
   * {@link #attach(String, String, String, Object, long)} with the
   * original operation id and the original message name for that.
   *
   * <p>This does not create a visit. If this delivery was not
   * admitted, the note is not stored.
   *
   * @param slot name of the note
   * @param json the JSON value to store, or {@code null} to delete
   * @param attachExpiresAt when the note may be dropped, epoch
   *                        milliseconds
   * @throws DomatarException {@code slot} is not a legal name, or
   *         the visit log cannot be written
   */
  public void attach(String slot, Object json, long attachExpiresAt)
      throws DomatarException;

  /**
   * {@link #attach(String, Object, long)} on an operation you name.
   *
   * <p>The message name is still the one this handler is running.
   * That is the right call when the note belongs on this message in
   * an earlier operation. It is the wrong call from
   * {@code Compensate}, whose message name is {@code Compensate}
   * rather than the message being undone.
   *
   * @param contextId the operation whose visit should hold the note
   * @param slot name of the note
   * @param json the JSON value to store, or {@code null} to delete
   * @param attachExpiresAt when the note may be dropped, epoch
   *                        milliseconds
   * @throws DomatarException {@code slot} is not a legal name, or
   *         the visit log cannot be written
   */
  public void attach(String contextId, String slot, Object json,
      long attachExpiresAt) throws DomatarException;

  /**
   * Store a JSON note on a visit you name completely.
   *
   * <p>Use this from {@code Compensate}: {@code contextId} and
   * {@code msgName} are the original operation and the original
   * message, not {@code Compensate}. The same rules apply as
   * {@link #attach(String, Object, long)} for {@code slot},
   * {@code json}, and {@code attachExpiresAt}. Nothing is stored
   * when that visit does not exist.
   *
   * @param contextId the operation
   * @param msgName the message name on that visit
   * @param slot name of the note
   * @param json the JSON value to store, or {@code null} to delete
   * @param attachExpiresAt when the note may be dropped, epoch
   *                        milliseconds
   * @throws DomatarException {@code slot} is not a legal name, or
   *         the visit log cannot be written
   */
  public void attach(String contextId, String msgName, String slot,
      Object json, long attachExpiresAt) throws DomatarException;

  /**
   * The note stored on this delivery's visit under {@code slot}.
   *
   * <p>The value is the JSON object, array, string, number, or
   * boolean stored by {@link #attach(String, Object, long)}.
   * {@code null} when
   * the note or the visit is missing.
   *
   * @param slot name of the note
   * @return the stored JSON value, or {@code null}
   * @throws DomatarException the visit log cannot be read
   */
  public Object attachment(String slot) throws DomatarException;

  /**
   * {@link #attachment(String)} for an operation you name.
   *
   * <p>The message name is still the one this handler is running.
   * To read the note on the message a {@code Compensate} is
   * undoing, use
   * {@link #attachment(String, String, String)}.
   *
   * @param contextId the operation
   * @param slot name of the note
   * @return the stored JSON value, or {@code null}
   * @throws DomatarException the visit log cannot be read
   */
  public Object attachment(String contextId, String slot) throws DomatarException;

  /**
   * The note on a visit you name completely.
   *
   * <p>{@code contextId} and {@code msgName} pick the operation and
   * the message. {@code slot} picks the note. This is how
   * {@code Compensate} reads what the original message stored.
   * {@code null} for any of the three arguments, or a missing note,
   * returns {@code null}.
   *
   * @param contextId the operation
   * @param msgName the message name on that visit
   * @param slot name of the note
   * @return the stored JSON value, or {@code null}
   * @throws DomatarException the visit log cannot be read
   */
  public Object attachment(String contextId, String msgName, String slot)
      throws DomatarException;
}
