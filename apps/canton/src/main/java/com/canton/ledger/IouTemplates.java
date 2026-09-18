/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.ledger;

import java.util.Arrays;
import java.util.Collections;

import com.canton.ledger.TemplateDesc.ChoiceDesc;
import com.canton.ledger.TemplateDesc.FieldDesc;

/**
 * Single source for the Iou template (Spec-CantonApp PART 10).
 */
public final class IouTemplates
{
  public static final String TEMPLATE_ID = "Iou";
  public static final String CLS_ID = "iou";

  private IouTemplates()
  {
  }

  public static TemplateDesc iou()
  {
    final FieldDesc issuer = new FieldDesc("Issuer", "Party");
    final FieldDesc owner = new FieldDesc("Owner", "Party");
    final FieldDesc amount = new FieldDesc("Amount", "Decimal");
    final FieldDesc currency = new FieldDesc("Currency", "Text");
    final FieldDesc newOwner = new FieldDesc("NewOwner", "Party");

    return new TemplateDesc(
        TEMPLATE_ID,
        Arrays.asList(issuer, owner, amount, currency),
        Collections.singletonList("Issuer"),
        Collections.singletonList("Owner"),
        Arrays.asList(
            new ChoiceDesc("Transfer", Collections.singletonList("Owner"), true,
                Collections.singletonList(newOwner)),
            new ChoiceDesc("Settle", Collections.singletonList("Issuer"), true,
                Collections.emptyList()),
            new ChoiceDesc("Archive", Collections.singletonList("Issuer"), true,
                Collections.emptyList())));
  }
}
