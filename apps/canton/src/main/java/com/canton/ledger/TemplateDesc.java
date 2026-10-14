/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.ledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One Daml template as the ledger client reports it: payload fields,
 * signatories, observers, and choices. The lists are copied and
 * unmodifiable.
 */
public final class TemplateDesc
{
  public final String templateId;
  public final List<FieldDesc> payloadFields;
  public final List<String> signatoryFields;
  public final List<String> observerFields;
  public final List<ChoiceDesc> choices;

  public TemplateDesc(final String templateId,
                      final List<FieldDesc> payloadFields,
                      final List<String> signatoryFields,
                      final List<String> observerFields,
                      final List<ChoiceDesc> choices)
  {
    this.templateId = templateId;
    this.payloadFields = copyFields(payloadFields);
    this.signatoryFields = copyStrings(signatoryFields);
    this.observerFields = copyStrings(observerFields);
    this.choices = copyChoices(choices);
  }

  public static final class FieldDesc
  {
    public final String name;
    public final String type;

    public FieldDesc(final String name, final String type)
    {
      this.name = name;
      this.type = type;
    }
  }

  /**
   * One choice. {@code controllerFields} are payload field names;
   * {@code ContractImpl} allows the choice only when each one equals
   * the caller's bound party. {@code consuming} means the choice
   * archives the contract.
   */
  public static final class ChoiceDesc
  {
    public final String name;
    public final List<String> controllerFields;
    public final boolean consuming;
    public final List<FieldDesc> argumentFields;

    public ChoiceDesc(final String name,
                      final List<String> controllerFields,
                      final boolean consuming,
                      final List<FieldDesc> argumentFields)
    {
      this.name = name;
      this.controllerFields = copyStrings(controllerFields);
      this.consuming = consuming;
      this.argumentFields = copyFields(argumentFields);
    }
  }

  private static List<String> copyStrings(final List<String> list)
  {
    if (list == null || list.isEmpty())
      return Collections.emptyList();
    return Collections.unmodifiableList(new ArrayList<>(list));
  }

  private static List<FieldDesc> copyFields(final List<FieldDesc> list)
  {
    if (list == null || list.isEmpty())
      return Collections.emptyList();
    return Collections.unmodifiableList(new ArrayList<>(list));
  }

  private static List<ChoiceDesc> copyChoices(final List<ChoiceDesc> list)
  {
    if (list == null || list.isEmpty())
      return Collections.emptyList();
    return Collections.unmodifiableList(new ArrayList<>(list));
  }
}
