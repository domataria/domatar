/*
 * Copyright (c) 2024 Domatar
 */

package com.travel.webui;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

import com.domatar.core.Context;
import com.domatar.servlet.DomatarServlet;
import com.domatar.util.Act;
import com.domatar.util.DomId;
import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMsg;
import com.domatar.util.ObjAttrs;
import com.travel.objimpl.TravelIds;

/**
 * Every action goes to the session user's app-travel. An unknown
 * action is returned as an error and is not dispatched.
 */
@WebServlet("/TravelWui/*")
public class TravelWui extends DomatarServlet
{
  private static final long serialVersionUID = 1L;

  private static final String[] FIELDS = {
      "Origin", "Destination", "Depart", "Return",
      "City", "CheckIn", "Nights", "Page",
      "Title", "FlightName", "HotelName", "Day1", "Day2", "Day3"
  };

  @Override
  protected JsonMsg getMsg(final HttpServletRequest req,
                           final DomId srcDomId,
                           final Context context,
                           final Act srcAct,
                           final DomatarMsgClient msgClient) throws DomatarException
  {
    final JsonMsg msg = new JsonMsg();
    final String action = getParam(req, "Action");

    if (srcAct == null || srcAct.actId == null)
    {
      msg.addError(action == null ? "" : action, "Unauthorized");
      return msg;
    }

    if (!facade(action))
    {
      msg.addError(action == null ? "" : action, "Unknown action");
      return msg;
    }

    final DomId app = TravelIds.subHost(srcAct.actId, TravelIds.APP);
    final ObjAttrs attrs = new ObjAttrs();

    for (int i = 0; i < FIELDS.length; i++)
      put(attrs, FIELDS[i], getParam(req, FIELDS[i]));

    msg.addRequestHead(srcDomId, app, context);
    msg.addClsId(TravelIds.HOME, "app");
    msg.addRequestBody(action, attrs);
    return msg;
  }

  private static boolean facade(final String action)
  {
    return "SearchFlights".equals(action)
        || "SearchHotels".equals(action)
        || "SearchSights".equals(action)
        || "BookTrip".equals(action)
        || "GetTrips".equals(action)
        || "GetTrip".equals(action);
  }

  private static void put(final ObjAttrs attrs, final String key, final String value)
      throws DomatarException
  {
    if (value != null && !value.isEmpty())
      attrs.addAttr(key, value);
  }
}
