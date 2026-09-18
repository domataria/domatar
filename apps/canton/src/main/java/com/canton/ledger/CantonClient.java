/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.ledger;

import java.util.List;
import java.util.Map;

import com.domatar.util.DomatarException;

/**
 * Ledger API subset this app uses. MockCanton implements it; a later
 * JSON Ledger API client will too.
 */
public interface CantonClient
{
  List<Contract> queryAcs(String party) throws DomatarException;

  SubmitResult submitCreate(String submitter, String templateId,
      Map<String, String> payload) throws DomatarException;

  SubmitResult submitExercise(String submitter, String contractId,
      String choice, Map<String, String> argument) throws DomatarException;

  TemplateDesc getTemplate(String templateId) throws DomatarException;

  List<String> listTemplates();
}
