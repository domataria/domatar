package com.domatar.servlet;

import java.net.URLDecoder;
import java.net.URLEncoder;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import com.domatar.util.DomatarException;

public class Cookies
{
  public static String getCookie(final Cookie[] cookies, final String cookieName) throws DomatarException
  {
    try
    {
      if (cookies == null)
        return null;

      for (final Cookie cookie : cookies)
      {
        if (cookie.getName().equals(cookieName))
          return URLDecoder.decode(cookie.getValue(), "UTF-8");
      }

      return null;
    }
    catch (final Exception e)
    {
      throw new DomatarException(e);
    }
  }

  public static void setCookie(final HttpServletResponse res, final String name,
                               final String value, final int age) throws DomatarException
  {
    try
    {
      final Cookie cookie = new Cookie(name, URLEncoder.encode(value, "UTF-8"));
      cookie.setMaxAge(age);
      cookie.setPath("/");
      res.addCookie(cookie);
    }
    catch (final Exception e)
    {
      throw new DomatarException(e);
    }
  }
}
