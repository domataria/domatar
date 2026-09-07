/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.core.DomatarConfig;
import com.domatar.util.Json;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.DomatarException;

/**
 * Versioned {@code Sec=} envelope beside the message (Spec PART 11.1).
 *
 * Ver is mandatory and first because this envelope changed the wire form
 * in four ways at once (out of the message Head, Seq removed, ContextId
 * and ActId added to the signed bytes, OriginSig deleted) while providers
 * upgrade independently.
 */
public final class SecWire
{
    public static final int VER = 1;
    public static final String PARAM = "Sec";

    private SecWire() {}

    /**
     * Emits {@code { Ver, Delegation, Binding, Path }} with Ver first.
     * Delegation and Binding are omitted when null. Path is always present.
     */
    public static String encode(final Provenance prov) throws DomatarException
    {
        final Provenance p = prov != null ? prov : Provenance.empty();
        final JsonMap env = new JsonHashMap();

        env.put("Ver", Integer.valueOf(VER));

        if (p.delegation() != null)
            env.put("Delegation", p.delegation().toMap());

        if (p.binding() != null)
            env.put("Binding", p.binding().toMap());

        env.put("Path", p.path().toWire());

        return Json.toJson(env);
    }

    /**
     * Parses a {@code Sec=} JSON body into Provenance.
     *
     * @throws DomatarException named reason: no Ver, unsupported Ver, no Path
     */
    public static Provenance decode(final String json) throws DomatarException
    {
        if (json == null || json.isEmpty())
            throw new DomatarException("Sec: no Ver");

        final JsonMap env = Json.parseMap(json);
        final Object verObj = env.get("Ver");

        if (verObj == null)
            throw new DomatarException("Sec: no Ver");

        final int ver;
        if (verObj instanceof Number)
            ver = ((Number) verObj).intValue();
        else
            throw new DomatarException("Sec: no Ver");

        final int min = DomatarConfig.getSecVerMin();
        final int max = DomatarConfig.getSecVerMax();

        if (ver < min || ver > max)
            throw new DomatarException("Sec: unsupported Ver " + ver);

        final JsonList pathList = env.getList("Path");

        if (pathList == null || pathList.size() == 0)
            throw new DomatarException("Sec: no Path");

        final Path path = Path.fromWire(pathList);

        if (path.isEmpty())
            throw new DomatarException("Sec: no Path");

        return Provenance.of(path,
                             Delegation.fromMap(env.getMap("Delegation")),
                             Binding.fromMap(env.getMap("Binding")));
    }
}
