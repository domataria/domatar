/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.core.DomatarConfig;
import com.domatar.util.Base64Encoder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Append-only export sink for genesis private keys (Spec-OwnIds.txt PART 7,
 * PART 15; Update-OwnIds.txt K3).
 *
 * <p><b>SIM / TEST ONLY.</b> This is a stand-in for the user's recovery
 * device (phone / passkey / offline codes). A production build must never
 * write the genesis private key to disk on a provider; the key is
 * user-held and cold. The vault exists so the local simulation can exercise
 * rebind without a real recovery device.
 *
 * <p>File format (tab-separated, one account per line):
 * <pre>
 *   actId TAB usrId TAB genesisPubKeyB64 TAB genesisPrvKeyB64
 * </pre>
 * Lines beginning with {@code #} are comments; blank lines are ignored.
 */
public final class GenesisVault
{
  private static final Logger LOG = Logger.getLogger(GenesisVault.class.getName());

  private GenesisVault()
  {
  }

  /**
   * Appends one vault line for {@code actId} if no line for that actId
   * already exists (idempotent). Creates the file and parent directories
   * when absent.
   */
  public static synchronized void export(final String actId,
                                         final String usrId,
                                         final byte[] genesisPubKey,
                                         final byte[] genesisPrivKey)
  {
    if (actId == null || actId.isEmpty())
      throw new IllegalArgumentException("actId required");
    if (genesisPubKey == null || genesisPrivKey == null)
      throw new IllegalArgumentException("genesis key bytes required");

    final Path path = Paths.get(DomatarConfig.getGenesisVaultPath());

    try
    {
      final Path parent = path.getParent();
      if (parent != null)
        Files.createDirectories(parent);

      if (Files.exists(path) && containsActId(path, actId))
        return;

      final boolean createHeader = !Files.exists(path) || Files.size(path) == 0;

      try (final BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
               StandardOpenOption.CREATE, StandardOpenOption.APPEND))
      {
        if (createHeader)
        {
          w.write("# Domatar genesis vault — SIM/TEST ONLY (Spec-OwnIds.txt PART 7/15).");
          w.newLine();
          w.write("# Format: actId<TAB>usrId<TAB>genesisPubKeyB64<TAB>genesisPrvKeyB64");
          w.newLine();
          w.write("# DO NOT use in production. Genesis private keys must stay user-held.");
          w.newLine();
          w.write("#");
          w.newLine();
        }

        w.write(actId);
        w.write('\t');
        w.write(usrId != null ? usrId : "");
        w.write('\t');
        w.write(Base64Encoder.encode(genesisPubKey));
        w.write('\t');
        w.write(Base64Encoder.encode(genesisPrivKey));
        w.newLine();
      }
    }
    catch (final IOException e)
    {
      throw new IllegalStateException("Failed to export genesis key to " + path, e);
    }
  }

  /**
   * Parses the vault file into {@code actId -> genesisPrivKey} bytes.
   * Returns an empty map if the file is absent.
   */
  public static Map<String, byte[]> loadPrivKeysByActId()
  {
    final Path path = Paths.get(DomatarConfig.getGenesisVaultPath());

    if (!Files.exists(path))
      return Collections.emptyMap();

    final Map<String, byte[]> map = new HashMap<>();

    try (final BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8))
    {
      String line;
      while ((line = r.readLine()) != null)
      {
        final String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#"))
          continue;

        final String[] cols = line.split("\t", -1);
        if (cols.length < 4)
        {
          LOG.warning("Ignoring malformed genesis-vault line: " + line);
          continue;
        }

        try
        {
          map.put(cols[0], Base64Encoder.decode(cols[3]));
        }
        catch (final RuntimeException e)
        {
          LOG.warning("Ignoring genesis-vault line with bad key for actId=" + cols[0] + ": " + e);
        }
      }
    }
    catch (final IOException e)
    {
      throw new IllegalStateException("Failed to read genesis vault " + path, e);
    }

    return map;
  }

  private static boolean containsActId(final Path path, final String actId) throws IOException
  {
    try (final BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8))
    {
      String line;
      while ((line = r.readLine()) != null)
      {
        final String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#"))
          continue;
        final int tab = line.indexOf('\t');
        final String id = tab < 0 ? line : line.substring(0, tab);
        if (actId.equals(id))
          return true;
      }
    }
    return false;
  }
}
