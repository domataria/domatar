/*
 * Copyright (c) 2024 Domatar
 */

package com.bookstore.webui;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import com.domatar.core.Context;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;

/**
 * Routes Catalog operations to the central catalog object on the bookstore host.
 *
 * Supported action: GetCatalog (Register and Deregister are internal server-to-server).
 * Spec: Spec-Bookstore.txt PART 7.3
 */
@WebServlet("/CatalogWui/*")
public class CatalogWui extends DomatarServlet
{
  private static final long serialVersionUID = 4756182930475618293L;

  @Override
  protected JsonMsg getMsg(final HttpServletRequest req,
                           final DomId srcDomId,
                           final Context context,
                           final Act srcAct,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();
    final String  opr = getParam(req, "Action");

    if (opr == null)
    {
      msg.addError(opr, "Missing action");
      return msg;
    }

    final DomId catalogId = new DomId("bookstore", "bookstore", "bookstore@bookstore", "catalog");

    if ("GetCatalog".equals(opr))
    {
      msg.addRequestHead(srcDomId, catalogId, context);
      msg.addClsId("bookstore", "catalog");
      msg.addRequestBody(opr, new ObjAttrs());
    }
    else
    {
      msg.addError(opr, "Unknown action");
    }

    return msg;
  }
}
