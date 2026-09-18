/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.ledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ledger DTO for one active contract. Not a Domatar object.
 */
public final class Contract
{
  public final String contractId;
  public final String templateId;
  public final Map<String, String> payload;
  public final List<String> signatories;
  public final List<String> observers;

  public Contract(final String contractId,
                  final String templateId,
                  final Map<String, String> payload,
                  final List<String> signatories,
                  final List<String> observers)
  {
    this.contractId = contractId;
    this.templateId = templateId;
    this.payload = unmodifiableCopy(payload);
    this.signatories = unmodifiableList(signatories);
    this.observers = unmodifiableList(observers);
  }

  private static Map<String, String> unmodifiableCopy(final Map<String, String> payload)
  {
    if (payload == null || payload.isEmpty())
      return Collections.emptyMap();
    return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }

  private static List<String> unmodifiableList(final List<String> list)
  {
    if (list == null || list.isEmpty())
      return Collections.emptyList();
    return Collections.unmodifiableList(new ArrayList<>(list));
  }
}
