/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.crypto;

import com.domatar.util.Json;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;
import com.domatar.util.DomatarException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Canonical JSON serialisation per Spec-Security.txt PART 4.
 *
 * Rules:
 *   - Object keys sorted by UTF-16 code unit order (Java String natural ordering).
 *   - No insignificant whitespace.
 *   - Strings encoded with standard JSON escaping.
 *   - Numbers serialised via their toString() representation.
 *   - UTF-8 output bytes.
 *
 * The byte string returned by each method is the input to every signature
 * computation in the system (hop records, delegation certificates,
 * hst directory records).
 */
public class CanonicalJson
{
    private CanonicalJson() {}

    /**
     * Canonical bytes for the given JsonMap.
     */
    public static byte[] canonicalize(final JsonMap map)
    {
        final StringBuilder sb = new StringBuilder();
        appendMap(sb, map, null);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parse {@code json} as a JSON object and return its canonical bytes.
     */
    public static byte[] canonicalize(final String json)
    {
        try
        {
            final JsonMap map = Json.parseMap(json);
            return canonicalize(map);
        }
        catch (final DomatarException e)
        {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Canonical bytes for {@code map} with the named top-level fields omitted.
     * Used to produce the byte string over which a signature field itself was
     * computed (e.g. exclude "HopSig" before verifying a hop).
     */
    public static byte[] canonicalizeExcluding(final JsonMap map, final String... fieldsToOmit)
    {
        final Set<String> exclude = new HashSet<>(Arrays.asList(fieldsToOmit));
        final StringBuilder sb = new StringBuilder();
        appendMap(sb, map, exclude);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Internal recursive serialiser
    // -------------------------------------------------------------------------

    private static void appendMap(final StringBuilder sb, final Map<?, ?> map,
                                  final Set<String> exclude)
    {
        // Sort keys by UTF-16 code unit order (Java String.compareTo).
        final TreeMap<String, Object> sorted = new TreeMap<>();

        for (final Map.Entry<?, ?> entry : map.entrySet())
        {
            final String key = (String) entry.getKey();

            if (exclude == null || !exclude.contains(key))
                sorted.put(key, entry.getValue());
        }

        sb.append('{');
        boolean first = true;

        for (final Map.Entry<String, Object> entry : sorted.entrySet())
        {
            if (!first)
                sb.append(',');

            first = false;
            appendString(sb, entry.getKey());
            sb.append(':');
            appendValue(sb, entry.getValue());
        }

        sb.append('}');
    }

    private static void appendValue(final StringBuilder sb, final Object value)
    {
        if (value == null)
        {
            sb.append("null");
        }
        else if (value instanceof Boolean)
        {
            sb.append(value);
        }
        else if (value instanceof Number)
        {
            sb.append(value.toString());
        }
        else if (value instanceof String)
        {
            appendString(sb, (String) value);
        }
        else if (value instanceof Map)
        {
            appendMap(sb, (Map<?, ?>) value, null);
        }
        else if (value instanceof List)
        {
            appendList(sb, (List<?>) value);
        }
        else
        {
            throw new IllegalArgumentException("Unsupported JSON value type: "
                    + value.getClass().getName());
        }
    }

    private static void appendList(final StringBuilder sb, final List<?> list)
    {
        sb.append('[');
        boolean first = true;

        for (final Object item : list)
        {
            if (!first)
                sb.append(',');

            first = false;
            appendValue(sb, item);
        }

        sb.append(']');
    }

    private static void appendString(final StringBuilder sb, final String s)
    {
        sb.append('"');

        for (int i = 0; i < s.length(); i++)
        {
            final char c = s.charAt(i);

            switch (c)
            {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c <= 0x001F)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
            }
        }

        sb.append('"');
    }
}
