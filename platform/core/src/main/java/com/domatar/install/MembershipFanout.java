/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.install;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import com.domatar.core.DomatarConfig;
import com.domatar.crypto.Binding;
import com.domatar.db.ObjDb;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Best-effort fan-out of membership writes to peer replicas
 * (Spec-Login-Multiple.txt PART 10.4 / K6).
 *
 * <p>Discovers the replica set from local membership peer rows via ObjDb
 * (distinct PrvId values, minus the local provider). Failures are
 * non-fatal: display-time ReconcileMembership repairs anything a push missed.
 */
public final class MembershipFanout
{
  private static final Logger LOG = Logger.getLogger(MembershipFanout.class.getName());

  private MembershipFanout() {}

  /**
   * Write {@code binding} into the local membership replica's binding obj,
   * then fan out SetBinding to other replicas (K7 / Phase 2 Task 2.5).
   * Local write is direct (no Sec required) so Setup?action=rebind-ownids
   * works; fan-out is best-effort via the caller's msgClient.
   */
  public static void publishBinding(final String actId,
                                    final Binding binding,
                                    final DomatarMsgClient msgClient)
  {
    if (actId == null || binding == null)
      return;

    final ObjAttrs attrs;

    try
    {
      attrs = bindingAttrs(binding);
    }
    catch (final DomatarException e)
    {
      LOG.warning("publishBinding attrs failed for actId=" + actId + ": " + e);
      return;
    }

    try
    {
      writeLocalBindingObj(actId, binding, attrs);
    }
    catch (final Exception e)
    {
      LOG.warning("publishBinding local write failed for actId=" + actId + ": " + e);
    }

    if (msgClient == null)
      return;

    try
    {
      push(actId, "SetBinding", attrs, msgClient);
    }
    catch (final Exception e)
    {
      LOG.warning("publishBinding fan-out failed for actId=" + actId + ": " + e);
    }
  }

  private static ObjAttrs bindingAttrs(final Binding binding) throws DomatarException
  {
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("ActId",         binding.actId);
    attrs.addAttr("GenesisPubKey", binding.genesisPubKeyB64);
    attrs.addAttr("OwnId",         binding.ownId);
    attrs.addAttr("OwnPubKey",     binding.ownPubKeyB64);
    attrs.addAttr("Version",       Long.toString(binding.version));
    attrs.addAttr("NotBefore",     Long.toString(binding.notBefore));
    attrs.addAttr("GenesisSig",    binding.genesisSig);

    return attrs;
  }

  private static void writeLocalBindingObj(final String actId,
                                           final Binding binding,
                                           final ObjAttrs attrs)
      throws Exception
  {
    final String prvId = DomatarConfig.getPrvId();
    final DomId bindingId = new DomId(
        DomId.subHstId("login", actId, prvId), "login", actId, "binding");

    final Obj existing = ObjDb.getObj(bindingId);

    long localVersion = -1L;

    if (existing != null && existing.attrs != null
        && existing.attrs.getAttr("Version") != null)
    {
      try
      {
        localVersion = Long.parseLong(existing.attrs.getAttr("Version"));
      }
      catch (final NumberFormatException ignored)
      {
        localVersion = -1L;
      }
    }

    if (binding.version <= localVersion)
      return;

    if (existing != null)
      ObjDb.modifyObj(existing.modify(null, null, null, null, null, attrs));
    else
      ObjDb.addObj(new Obj(bindingId, "login", "binding",
                           "Binding", "actId->ownId binding (replicated)", attrs));
  }

  /**
   * Write Binding fields onto the local domatar substrate binding obj.
   * N-local; does not fan out (KD6).
   */
  public static void writeLocalSubstrateBinding(final String actId,
                                                final String prvId,
                                                final Binding binding)
      throws DomatarException
  {
    if (actId == null || prvId == null || binding == null)
      throw new DomatarException(
          "writeLocalSubstrateBinding requires actId, prvId, binding");

    final DomId bindingId = UserSubstrateIds.binding(actId, prvId);
    final Obj existing = ObjDb.getObj(bindingId);
    final ObjAttrs attrs = existing != null && existing.attrs != null
        ? new ObjAttrs(existing.attrs) : new ObjAttrs();

    attrs.addAttr("ActId",         binding.actId);
    attrs.addAttr("GenesisPubKey", binding.genesisPubKeyB64);
    attrs.addAttr("OwnId",         binding.ownId);
    attrs.addAttr("OwnPubKey",     binding.ownPubKeyB64);
    attrs.addAttr("Version",       Long.toString(binding.version));
    attrs.addAttr("NotBefore",     Long.toString(binding.notBefore));
    attrs.addAttr("GenesisSig",    binding.genesisSig);

    if (existing != null)
      ObjDb.modifyObj(existing.modify(null, null, null, null, null, attrs));
    else
      ObjDb.addObjIfMissing(bindingId, "domatar", "binding",
          "Binding", "actId->ownId binding", attrs);
  }

  /**
   * Merge Delegation attrs onto the local domatar substrate binding obj.
   * N-local only; does not fan out (KD6).
   */
  public static void writeLocalDelegation(final String actId,
                                          final String prvId,
                                          final String delegationJson,
                                          final String delegNotAfter,
                                          final String delegSig)
      throws DomatarException
  {
    if (actId == null || prvId == null)
      throw new DomatarException(
          "writeLocalDelegation requires actId, prvId");

    final DomId bindingId = UserSubstrateIds.binding(actId, prvId);
    final Obj existing = ObjDb.getObj(bindingId);
    final ObjAttrs attrs = existing != null && existing.attrs != null
        ? new ObjAttrs(existing.attrs) : new ObjAttrs();

    if (delegationJson != null)
      attrs.addAttr("Delegation", delegationJson);

    if (delegNotAfter != null)
      attrs.addAttr("DelegNotAfter", delegNotAfter);

    if (delegSig != null)
      attrs.addAttr("DelegSig", delegSig);

    if (existing != null)
      ObjDb.modifyObj(existing.modify(null, null, null, null, null, attrs));
    else
      ObjDb.addObjIfMissing(bindingId, "domatar", "binding",
          "Binding", "actId->ownId binding", attrs);
  }

  /**
   * Push {@code opr} with {@code attrs} to every remote membership replica
   * for {@code actId}. Non-fatal.
   */
  public static void push(final String actId,
                          final String opr,
                          final ObjAttrs attrs,
                          final DomatarMsgClient msgClient)
  {
    if (actId == null || opr == null || msgClient == null)
      return;

    final String localPrvId = DomatarConfig.getPrvId();
    boolean objectOnly = false;

    try
    {
      objectOnly = attrs != null && "False".equals(attrs.getAttr("IsLoginHome"));
    }
    catch (final DomatarException ignored)
    {
      objectOnly = false;
    }

    for (final String remotePrvId : discoverRemotePrvIds(actId, localPrvId, msgClient))
    {
      try
      {
        final DomId dst;
        final String clsAppId;

        if (objectOnly)
        {
          dst = UserSubstrateIds.membership(actId, remotePrvId);
          clsAppId = "domatar";
        }
        else
        {
          dst = new DomId(
              DomId.subHstId("login", actId, remotePrvId),
              "login", actId, "membership");
          clsAppId = "login";
        }

        final JsonMsg msg = new JsonMsg();

        msg.addRequestBody(opr, attrs);
        msg.addClsId(clsAppId, "membership");

        final JsonMsg resp = msgClient.send(dst, msg);
        final String err = resp != null ? resp.getError() : "null-resp";
        final String errMsg = resp != null ? resp.getErrorMsg() : "";
        if (!"Success".equals(err))
          LOG.warning("push " + opr + " to prvId=" + remotePrvId
              + " actId=" + actId + " error=" + err + " msg=" + errMsg);
      }
      catch (final Exception e)
      {
        LOG.warning("push " + opr + " to prvId=" + remotePrvId
            + " failed for actId=" + actId + ": " + e);
      }
    }
  }

  /**
   * Distinct PrvId values from the local membership peers, excluding
   * {@code localPrvId}. Reads peer rows via ObjDb (no Sec required).
   */
  public static Set<String> discoverRemotePrvIds(final String actId,
                                                  final String localPrvId,
                                                  final DomatarMsgClient msgClient)
  {
    final Set<String> result = new LinkedHashSet<String>();

    if (actId == null)
      return result;

    try
    {
      final String local = localPrvId != null ? localPrvId : DomatarConfig.getPrvId();
      final String hstId = DomId.subHstId("login", actId, local);
      final List<Obj> rows = ObjDb.getObjPrefix(hstId, "login", actId, "peer", null, 1000);

      for (final Obj row : rows)
      {
        if (row == null || row.attrs == null)
          continue;

        final String prvId = row.attrs.getAttr("PrvId");

        if (prvId == null || prvId.isEmpty())
          continue;

        if (local != null && local.equals(prvId))
          continue;

        result.add(prvId);
      }
    }
    catch (final Exception e)
    {
      LOG.warning("discoverRemotePrvIds failed for actId=" + actId + ": " + e);
    }

    return result;
  }
}
