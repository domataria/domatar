/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.objimpl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.canton.ledger.CantonClients;
import com.canton.ledger.Contract;
import com.canton.ledger.IouTemplates;
import com.domatar.db.LnkDb;
import com.domatar.db.ObjDb;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonList;
import com.domatar.util.Lnk;
import com.domatar.util.Obj;
import com.domatar.util.ObjAttrs;

/**
 * Projects this user's ACS onto handle objects under Active.
 */
final class HandleSync
{
  private HandleSync()
  {
  }

  static DomId subHost(final String actId, final String objId)
      throws DomatarException
  {
    return new DomId(DomId.subHstId("canton", actId), "canton", actId, objId);
  }

  static DomId partyDomId(final String actId) throws DomatarException
  {
    return subHost(actId, "party");
  }

  static DomId activeDomId(final String actId) throws DomatarException
  {
    return subHost(actId, "active");
  }

  static String boundParty(final String actId) throws DomatarException
  {
    final Obj party = ObjDb.getObj(partyDomId(actId));
    if (party == null || party.attrs == null)
      throw new DomatarException("Bind a Party first");

    final String partyId = party.attrs.getAttr("PartyId");
    if (partyId == null || partyId.isEmpty())
      throw new DomatarException("Bind a Party first");
    return partyId;
  }

  static ObjAttrs attrsFrom(final Contract c) throws DomatarException
  {
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("ContractId", c.contractId);
    attrs.addAttr("TemplateId", c.templateId);
    attrs.addAttr("PackageId", "");
    if (c.payload != null)
    {
      for (final String key : c.payload.keySet())
      {
        final String value = c.payload.get(key);
        attrs.addAttr(key, value != null ? value : "");
      }
    }
    attrs.addAttr("Signatories", jsonStrings(c.signatories));
    attrs.addAttr("Observers", jsonStrings(c.observers));
    return attrs;
  }

  static String clsIdOf(final Contract c)
  {
    if (c != null && IouTemplates.TEMPLATE_ID.equals(c.templateId))
      return IouTemplates.CLS_ID;
    return "contract";
  }

  static Contract findLive(final String actId, final String contractId)
      throws DomatarException
  {
    if (contractId == null || contractId.isEmpty())
      return null;

    final String party = boundParty(actId);
    for (final Contract c : CantonClients.get().queryAcs(party))
    {
      if (contractId.equals(c.contractId))
        return c;
    }
    return null;
  }

  static Contract refreshOrDrop(final Obj handle) throws DomatarException
  {
    final Contract c = findLive(handle.domId.actId, handle.domId.objId);

    if (c == null)
    {
      dropHandle(handle.domId.actId, handle.domId.objId);
      return null;
    }

    final ObjAttrs attrs = attrsFrom(c);
    handle.attrs.toMap().clear();
    handle.attrs.addAll(attrs.toMap());
    final String name = objNameOf(c);
    final String desc = clip(
        c.templateId != null && !c.templateId.isEmpty() ? c.templateId : "contract", 100);
    ObjDb.modifyObj(new Obj(handle.domId, handle.clsAppId, handle.clsId,
                             name, desc, handle.attrs));
    return c;
  }

  static void upsertHandle(final String actId, final Contract c)
      throws DomatarException
  {
    final String clsId = clsIdOf(c);
    final DomId handle = subHost(actId, c.contractId);
    final ObjAttrs attrs = attrsFrom(c);
    final String name = objNameOf(c);
    final String desc = clip(
        c.templateId != null && !c.templateId.isEmpty() ? c.templateId : "contract", 100);
    final Obj existing = ObjDb.getObj(handle);

    if (existing == null)
      ObjDb.addObjIfMissing(handle, "canton", clsId, name, desc, attrs);
    else
      ObjDb.modifyObj(new Obj(handle, "canton", clsId, name, desc, attrs));

    final DomId active = activeDomId(actId);
    if (LnkDb.getLnk(active, handle, "canton", clsId) == null)
      LnkDb.addLnk(new Lnk(active, handle,
                            "canton", clsId,
                            name, desc,
                            "canton", clsId,
                            c.templateId, System.nanoTime()));
  }

  static void dropHandle(final String actId, final String contractId)
      throws DomatarException
  {
    final DomId active = activeDomId(actId);
    final DomId handle = subHost(actId, contractId);

    LnkDb.deleteLnks(active, handle, "canton", "iou", null, null);
    LnkDb.deleteLnks(active, handle, "canton", "contract", null, null);
    if (ObjDb.getObj(handle) != null)
      ObjDb.deleteObj(handle);
  }

  static int sync(final String actId) throws DomatarException
  {
    final String party = boundParty(actId);
    final List<Contract> acs = CantonClients.get().queryAcs(party);
    final Set<String> live = new HashSet<>();

    for (final Contract c : acs)
    {
      upsertHandle(actId, c);
      live.add(c.contractId);
    }

    final List<Lnk> lnks = LnkDb.getLnks(activeDomId(actId), "canton", null,
                                          null, null, 10000, false);
    for (final Lnk lnk : lnks)
    {
      if (lnk.lnkDomId == null || live.contains(lnk.lnkDomId.objId))
        continue;
      dropHandle(actId, lnk.lnkDomId.objId);
    }
    return acs.size();
  }

  private static String objNameOf(final Contract c)
  {
    String name = null;
    if (c.payload != null)
    {
      final String amount = c.payload.get("Amount");
      if (IouTemplates.TEMPLATE_ID.equals(c.templateId))
        name = "Iou " + (amount != null ? amount : "") + " "
            + firstNonEmpty(c.payload, "Currency");
      else
      {
        final String symbol = firstNonEmpty(c.payload, "Symbol", "Currency");
        if (symbol != null)
          name = symbol + (amount != null && !amount.isEmpty() ? " " + shortAmount(amount) : "");
      }
    }
    if (name == null || name.isEmpty())
      name = c.templateId != null && !c.templateId.isEmpty() ? c.templateId : c.contractId;
    return clip(name, 40);
  }

  private static String firstNonEmpty(final java.util.Map<String, String> payload,
                                      final String... keys)
  {
    if (payload == null || keys == null)
      return null;
    for (final String key : keys)
    {
      final String value = payload.get(key);
      if (value != null && !value.isEmpty())
        return value;
    }
    return null;
  }

  private static String shortAmount(final String amount)
  {
    final int dot = amount.indexOf('.');
    if (dot < 0)
      return amount;
    int end = amount.length();
    while (end > dot + 1 && amount.charAt(end - 1) == '0')
      end--;
    if (end == dot + 1)
      return amount.substring(0, dot);
    return amount.substring(0, Math.min(end, dot + 3));
  }

  private static String clip(final String value, final int max)
  {
    if (value == null)
      return "";
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static String jsonStrings(final List<String> values)
      throws DomatarException
  {
    final JsonList list = new JsonArrayList();

    if (values != null)
    {
      for (final String value : values)
        list.add(value);
    }
    return Json.toJson(list);
  }
}
