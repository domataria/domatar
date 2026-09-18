/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.ledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SubmitResult
{
  public final List<String> archived;
  public final List<Contract> created;

  public SubmitResult(final List<String> archived, final List<Contract> created)
  {
    this.archived = copy(archived);
    this.created = copyContracts(created);
  }

  private static List<String> copy(final List<String> list)
  {
    if (list == null || list.isEmpty())
      return Collections.emptyList();
    return Collections.unmodifiableList(new ArrayList<>(list));
  }

  private static List<Contract> copyContracts(final List<Contract> list)
  {
    if (list == null || list.isEmpty())
      return Collections.emptyList();
    return Collections.unmodifiableList(new ArrayList<>(list));
  }
}
