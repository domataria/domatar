package com.domatar.util;

import com.domatar.db.HstDb;

public class DomId
{
  /**
   * Host-id segment separator (D6, Spec-Security.txt Phase 2).
   * Separates the app-prefix from the actId section in a per-user sub-host
   * name, e.g. {@code "navigator-<actId>"}. Using '-' (not '~') ensures
   * no collision with the Base64Encoder alphabet that actId fingerprints
   * use (which includes '~').
   */
  public static final String HOST_SEP = "-";

  /**
   * Builds a per-user sub-host id from an app prefix and an actId.
   * E.g. {@code subHstId("navigator", actId)} -> {@code "navigator-<actId>"}.
   */
  public static String subHstId(final String appPrefix, final String actId)
  {
    return appPrefix + HOST_SEP + actId;
  }

  /**
   * Builds a provider-qualified replica host id
   * {@code <appPrefix>-<actId>-<prvId>} (Spec-Login-Multiple.txt PART 5.1).
   * Preserves the directory invariant "one hstId -> one location".
   */
  public static String subHstId(final String appPrefix, final String actId,
                                final String prvId)
  {
    return appPrefix + HOST_SEP + actId + HOST_SEP + prvId;
  }

  /**
   * Local Desktop/Navigator host for this provider: prefer the qualified
   * {@code <app>-&lt;actId&gt;-&lt;prvId&gt;} when that hst row exists, otherwise
   * fall back to the bare {@code <app>-&lt;actId&gt;} (pre-migration accounts).
   * After Phase 4 {@code migrate-desktop-sync} bare hosts are gone, so this
   * returns the qualified id in practice. The bare fallback is kept as a
   * harmless safety net — it cannot recreate a bare host.
   */
  public static String localSubHstId(final String appPrefix,
                                     final String actId,
                                     final String prvId)
  {
    if (appPrefix == null || actId == null)
      return null;

    if (prvId != null && !prvId.isEmpty())
    {
      final String qualified = subHstId(appPrefix, actId, prvId);

      try
      {
        if (HstDb.getHst(qualified) != null)
          return qualified;
      }
      catch (final Exception ignored)
      {
        // fall through to bare
      }
    }

    return subHstId(appPrefix, actId);
  }

  /**
   * Parses the trailing {@code prvId} from a provider-qualified replica host
   * id {@code <appId>-<actId>-<prvId>} (Spec-Login-Multiple.txt PART 5.2).
   * Width-independent (Spec-ActId-Versioning.txt PART 7 / KD7).
   * Returns null if {@code replicaHstId} is not that shape.
   */
  public static String replicaPrvId(final String replicaHstId)
  {
    final String[] parts = splitReplicaHst(replicaHstId);

    if (parts == null || !isFingerprintActId(parts[1]))
      return null;

    return parts[2];
  }

  /**
   * Parses the embedded {@code actId} from a provider-qualified replica host
   * id {@code <appId>-<actId>-<prvId>} (Spec-Login-Multiple.txt PART 5.2).
   * Width-independent (Spec-ActId-Versioning.txt PART 7 / KD7).
   * Returns null if {@code replicaHstId} is not that shape.
   */
  public static String replicaActId(final String replicaHstId)
  {
    final String[] parts = splitReplicaHst(replicaHstId);

    if (parts == null || !isFingerprintActId(parts[1]))
      return null;

    return parts[1];
  }

  /**
   * Structural split of {@code <app>-<actId>-<prvId>} by first/last HOST_SEP.
   * Returns {@code {appPrefix, actId, prvId}} or null. Does not apply the
   * fingerprint-shape gate (callers do). Package-visible for width tests.
   */
  static String[] splitReplicaHst(final String replicaHstId)
  {
    if (replicaHstId == null)
      return null;

    final int firstSep = replicaHstId.indexOf(HOST_SEP);
    final int lastSep  = replicaHstId.lastIndexOf(HOST_SEP);

    if (firstSep < 0 || lastSep <= firstSep)
      return null;

    final String appPrefix = replicaHstId.substring(0, firstSep);
    final String actId     = replicaHstId.substring(firstSep + 1, lastSep);
    final String prvId     = replicaHstId.substring(lastSep + 1);

    if (appPrefix.isEmpty() || actId.isEmpty() || prvId.isEmpty())
      return null;

    return new String[] { appPrefix, actId, prvId };
  }

  public final String hstId;
  public final String appId;
  public final String actId;
  public final String objId;

  public DomId(String hstId, String appId, String actId, String objId) throws DomatarException
  {
    this.hstId = hstId;
    this.appId = appId;
    this.actId = actId;
    this.objId = objId;

    if (!valid())
      throw new DomatarException("DomId " + toString() + " not valid");
  }

  public DomId(String domIdStr) throws DomatarException
  {
    if (domIdStr == null)
      throw new DomatarException("DomId is null");

    String[] idParts = domIdStr.split("\\.");

    if (idParts.length != 4)
      throw new DomatarException("DomId " + domIdStr + " not valid");

    hstId = idParts[0];
    appId = idParts[1];
    actId = idParts[2];
    objId = idParts[3];

    if (!valid())
      throw new DomatarException("DomId " + domIdStr + " not valid");
  }

  @Override
  public String toString()
  {
    return hstId + "." + appId + "." + actId + "." + objId;
  }

  @Override
  public boolean equals (Object obj)
  {
    if (obj == this)
      return true;

    DomId domId;

    if (obj instanceof DomId)
      domId = (DomId)obj;
    else if (obj instanceof String)
    {
      try
      {
        domId = new DomId((String)obj);
      }
      catch (DomatarException e)
      {
        return false;
      }
    }
    else
      return false;

    return this.hstId.equals(domId.hstId) &&
           this.appId.equals(domId.appId) &&
           this.actId.equals(domId.actId) &&
           this.objId.equals(domId.objId);
  }

  public static String getUsrHandle(String usrId)
  {
    if (usrId == null)
      return null;

    int ind = usrId.indexOf('@');

    if (ind < 0)
      return usrId;

    String suffix = usrId.substring(ind + 1, usrId.length());

    if ("quippin".equals(suffix))
      return usrId.substring(0, ind);

    return usrId;
  }

  public static String getUsrHst(String usrId)
  {
    if (usrId == null)
      return null;

    int ind = usrId.indexOf('@');

    if (ind < 0)
      return usrId;

    return usrId.substring(ind + 1, usrId.length());
  }

  /**
   * Get the appId out of a usrId or actId of shape "<localname>@<appId>".
   * Returns null if input is null or contains no '@' or has an empty
   * suffix - all of which are malformed identifiers under the federated
   * login model (Spec-Login.txt PART 3.2). Callers treat null as
   * "not routable" / "not verified".
   */
  public static String getAppId(String actIdOrUsrIdStr)
  {
    if (actIdOrUsrIdStr == null)
      return null;

    int ind = actIdOrUsrIdStr.lastIndexOf('@');

    if (ind < 0)
      return null;

    String suffix = actIdOrUsrIdStr.substring(ind + 1);

    if (suffix.length() == 0)
      return null;

    return suffix;
  }

  private boolean valid()
  {
    return hstIdValid() && appIdValid() && actIdValid() && objIdValid();
  }

  private boolean hstIdValid()
  {
    if (hstId.length() == 0)
      return true;

    for (int i = 0; i < hstId.length(); i++)
      if (!isHstIdChar(hstId.charAt(i)))
        return false;

    return true;
  }

  private boolean appIdValid()
  {
    if (appId.length() == 0)
      return false;

    for (int i = 0; i < appId.length(); i++)
      if (!isAppIdChar(appId.charAt(i)))
        return false;

    return true;
  }

  private boolean actIdValid()
  {
    if (actId.length() == 0)
      return false;

    for (int i = 0; i < actId.length(); i++)
      if (!isActIdChar(actId.charAt(i)))
        return false;

    return true;
  }

  /**
   * Returns true when {@code actId} is a self-certifying key-fingerprint
   * actId: exactly 32 characters, no '@', all chars in the fingerprint
   * alphabet (letters, digits, '-', '_', '~').
   * Used by the migration idempotency check and by routing decisions.
   * Recognises the v1 shape only; prefer the {@link #isFingerprintActId(String, int)}
   * overload in new code (Spec-ActId-Versioning.txt PART 4.3).
   */
  public static boolean isFingerprintActId(final String actId)
  {
    if (actId == null || actId.length() != 32)
      return false;

    if (actId.indexOf('@') >= 0)
      return false;

    for (int i = 0; i < actId.length(); i++)
    {
      final char c = actId.charAt(i);
      if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '~')
        return false;
    }

    return true;
  }

  /**
   * True iff actId matches the shape produced by fingerprint {@code version}
   * (v1 ⇒ exactly 32 chars of the fingerprint alphabet).
   */
  public static boolean isFingerprintActId(final String actId, final int version)
  {
    if (version == 1)
      return isFingerprintActId(actId);

    return false;
  }

  /**
   * Returns true for every character that may appear in an actId.
   * Accepted set: letters/digits (ASCII), '-', '_', '~', '@'.
   * The '@' is retained so legacy {@code name@app} actIds remain
   * representable during migration, and so the system token
   * {@code act@act} stays valid.
   */
  static boolean isActIdChar(final char c)
  {
    return c == '-' || c == '_' || c == '~' || c == '@'
        || (Character.isLetterOrDigit(c) && c < 128);
  }

  private boolean objIdValid()
  {
    // Empty objId is allowed for "namespace-only base" DomIds used by install
    // routines (e.g. DomatarInstall.installOnBase) that need to carry a
    // hstId/appId/actId tuple without identifying a specific object.
    // Such DomIds are never persisted directly; child DomIds always supply a
    // concrete objId.  Mirrors the empty-hstId exemption in hstIdValid().
    if (objId.length() == 0)
      return true;

    for (int i = 0; i < objId.length(); i++)
      if (!isObjIdChar(objId.charAt(i)))
        return false;

    return true;
  }

  static boolean isHstIdChar(char c)
  {
    return c == '-' || c == '_' || c == '~' || c == '@' || (Character.isLetterOrDigit(c) && c < 128);
  }

  static boolean isAppIdChar(char c)
  {
    return c == '-' || c == '_' || (Character.isLetterOrDigit(c) && c < 128);
  }

  static boolean isActHandleChar(char c)
  {
    return c == '-' || c == '_' || (Character.isLetterOrDigit(c) && c < 128);
  }

  static boolean isObjIdChar(char c)
  {
    return c == '-' || c == '_' || c == '~' || c == '@' || (Character.isLetterOrDigit(c) && c < 128);
  }
}
