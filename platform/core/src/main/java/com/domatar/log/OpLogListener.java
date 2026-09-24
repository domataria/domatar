package com.domatar.log;

import com.domatar.db.DbConnection;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;

/**
 * Visit side effect. Authorization stays on {@code ObjImpl.rights()}
 * (re-entry's cycle deny belongs there). A listener does not decide
 * whether a second arrival runs.
 *
 * <p>Registration order is call order. {@code onFirstAdmit} participants
 * share one transaction; a throw rolls the visit back.
 */
public interface OpLogListener
{
  /**
   * Null: this listener does not authorize. True: allow, and do not
   * call {@code rights()}. False: deny, no visit.
   */
  default Boolean authorize(final OpLogAdmit admit) throws DomatarException
  {
    return null;
  }

  /** False denies before any insert. */
  default boolean beforeAdmit(final OpLogAdmit admit) throws DomatarException
  {
    return true;
  }

  /** True: write on the connection that inserts the visit row. */
  default boolean joinFirstAdmit(final OpLogAdmit admit) throws DomatarException
  {
    return false;
  }

  /**
   * Called only when this admit inserted the row, on that connection.
   * Throw {@link OpLogDeny} to roll the visit back and deny.
   */
  default void onFirstAdmit(final DbConnection conn, final OpLogAdmit admit)
      throws DomatarException
  {
  }

  default void onHandled(final DomatarMsgClient client, final JsonMsg inMsg,
      final Obj obj, final String reply) throws DomatarException
  {
  }

  default void onCompensated(final String origContextId, final String origMsgName,
      final DomatarMsgClient client) throws DomatarException
  {
  }

  /** Platform-owned attachment name, or null. Handlers cannot write it. */
  default String reservedSlot()
  {
    return null;
  }
}
