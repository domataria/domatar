/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.webui;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.canton.ledger.CantonClients;
import com.canton.ledger.Contract;
import com.canton.ledger.MockCanton;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;

/**
 * Workshop operator for the in-process mock ACS. Not a
 * {@link com.domatar.servlet.DomatarServlet} translator: it does not
 * send to a handle. Deleted with {@link MockCanton}.
 */
@WebServlet("/MockNetworkWui/*")
public class MockNetworkWui extends HttpServlet
{
  private static final long serialVersionUID = 1L;

  private static final Set<String> SKIP_PARAMS = Set.of(
      "Action", "ContractDomId", "ContractId", "TemplateId",
      "Signatories", "Observers", "PackageId");

  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse res)
      throws IOException
  {
    doAction(req, res);
  }

  @Override
  protected void doPost(final HttpServletRequest req, final HttpServletResponse res)
      throws IOException
  {
    doAction(req, res);
  }

  private void doAction(final HttpServletRequest req, final HttpServletResponse res)
      throws IOException
  {
    res.setContentType("text/html; charset=UTF-8");
    res.setCharacterEncoding("UTF-8");
    final PrintWriter out = res.getWriter();
    final String opr = param(req, "Action");

    try
    {
      out.print(handle(req, opr != null ? opr : "Get").getWui());
    }
    catch (final DomatarException e)
    {
      out.print(errorJson(opr, e.getMessage()));
    }
    catch (final Exception e)
    {
      out.print(errorJson(opr, e.getMessage() != null ? e.getMessage() : "Mock network failed"));
    }
  }

  private static JsonMsg handle(final HttpServletRequest req, final String opr)
      throws DomatarException
  {
    final MockCanton mock = CantonClients.mockOrNull();

    if (mock == null)
    {
      final JsonMsg msg = new JsonMsg();

      msg.addError(opr, "Mock Canton is not available");
      return msg;
    }

    final String contractId = contractIdOf(req);
    final Contract live;

    if ("Patch".equals(opr))
      live = mock.patchPayload(contractId, payloadFields(req));
    else if ("Get".equals(opr))
    {
      live = mock.getContract(contractId);
      if (live == null)
        throw new DomatarException("Unknown contract: " + contractId);
    }
    else
    {
      final JsonMsg msg = new JsonMsg();

      msg.addError(opr, "Unknown action");
      return msg;
    }

    final JsonMsg msg = new JsonMsg();

    msg.addResponseBody(opr, attrsOf(live));
    return msg;
  }

  private static String contractIdOf(final HttpServletRequest req) throws DomatarException
  {
    final String rawId = param(req, "ContractId");

    if (rawId != null)
      return rawId;

    final String dom = param(req, "ContractDomId");

    if (dom == null)
      throw new DomatarException("Missing ContractDomId");

    try
    {
      return new DomId(dom).objId;
    }
    catch (final Exception e)
    {
      throw new DomatarException("Invalid ContractDomId: " + dom);
    }
  }

  private static Map<String, String> payloadFields(final HttpServletRequest req)
  {
    final Map<String, String> fields = new LinkedHashMap<>();
    final Map<String, String[]> params = req.getParameterMap();

    if (params == null)
      return fields;

    for (final Map.Entry<String, String[]> e : params.entrySet())
    {
      final String key = e.getKey();

      if (key == null || SKIP_PARAMS.contains(key))
        continue;
      final String[] values = e.getValue();

      fields.put(key, (values != null && values.length > 0 && values[0] != null)
          ? values[0] : "");
    }
    return fields;
  }

  private static ObjAttrs attrsOf(final Contract c) throws DomatarException
  {
    final ObjAttrs attrs = new ObjAttrs();

    attrs.addAttr("ContractId", c.contractId);
    attrs.addAttr("TemplateId", c.templateId != null ? c.templateId : "");
    if (c.payload != null)
    {
      for (final Map.Entry<String, String> e : c.payload.entrySet())
        attrs.addAttr(e.getKey(), e.getValue() != null ? e.getValue() : "");
    }
    return attrs;
  }

  private static String param(final HttpServletRequest req, final String name)
  {
    final String value = req.getParameter(name);

    if (value == null)
      return null;
    final String trimmed = value.trim();

    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String errorJson(final String opr, final String message)
  {
    try
    {
      final JsonMsg msg = new JsonMsg();

      msg.addError(opr != null ? opr : "Get",
          message != null ? message : "Mock network failed");
      return msg.getWui();
    }
    catch (final DomatarException e)
    {
      return "{\"Error\":\"Failure\",\"ErrorMsg\":\"Mock network failed\"}";
    }
  }
}
