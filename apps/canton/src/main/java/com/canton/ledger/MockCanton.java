/*
 * Copyright (c) 2024 Domatar
 */

package com.canton.ledger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.canton.ledger.TemplateDesc.ChoiceDesc;
import com.domatar.util.DomatarException;
import com.domatar.util.Json;
import com.domatar.util.JsonArrayList;
import com.domatar.util.JsonHashMap;
import com.domatar.util.JsonList;
import com.domatar.util.JsonMap;

/**
 * In-process mockup of CantonClient. Persistence is this class's
 * JSON file, not a CantonClient method.
 */
public final class MockCanton implements CantonClient
{
  private final File store;
  private final Map<String, Contract> contracts = new LinkedHashMap<>();
  private int nextId = 1;

  public MockCanton() throws DomatarException
  {
    this(defaultStoreFile());
  }

  public MockCanton(final File store) throws DomatarException
  {
    this.store = store;
    if (store != null && store.isFile())
      load();
  }

  public static File defaultStoreFile()
  {
    final String override = System.getenv("CANTON_MOCK_PATH");

    if (override != null && !override.isEmpty())
      return new File(override);

    final String keyPath = System.getenv("DOMATAR_PROVIDER_KEY_PATH");
    final File dir = (keyPath != null && !keyPath.isEmpty())
        ? new File(keyPath).getParentFile()
        : new File(".");
    final String prvIdEnv = System.getenv("DOMATAR_PRVID");
    final String prvId = (prvIdEnv != null && !prvIdEnv.isEmpty()) ? prvIdEnv : "local";
    final File parent = (dir != null) ? dir : new File(".");

    return new File(parent, "canton-mock-" + prvId + ".json");
  }

  @Override
  public synchronized List<String> listTemplates()
  {
    return Collections.singletonList(IouTemplates.TEMPLATE_ID);
  }

  @Override
  public synchronized TemplateDesc getTemplate(final String templateId) throws DomatarException
  {
    if (IouTemplates.TEMPLATE_ID.equals(templateId))
      return IouTemplates.iou();
    final TemplateDesc demo = DemoPortfolio.template(templateId);
    if (demo != null)
      return demo;
    throw new DomatarException("Unknown template: " + templateId);
  }

  @Override
  public synchronized List<Contract> queryAcs(final String party) throws DomatarException
  {
    final List<Contract> out = new ArrayList<>();

    if (party == null)
      return out;

    for (final Contract c : contracts.values())
    {
      if (c.signatories.contains(party) || c.observers.contains(party))
        out.add(c);
    }
    return out;
  }

  @Override
  public synchronized SubmitResult submitCreate(final String submitter, final String templateId,
      final Map<String, String> payload) throws DomatarException
  {
    final TemplateDesc t = getTemplate(templateId);
    final Map<String, String> body = payload != null ? payload : Collections.emptyMap();

    for (final String field : t.signatoryFields)
    {
      final String value = body.get(field);

      if (value == null || !value.equals(submitter))
        throw new DomatarException("Submitter is not a signatory");
    }

    final String id = "cid-" + nextId++;
    final Contract created = new Contract(id, templateId, body,
        partiesFrom(t.signatoryFields, body),
        partiesFrom(t.observerFields, body));

    contracts.put(id, created);
    save();
    return new SubmitResult(Collections.emptyList(), Collections.singletonList(created));
  }

  @Override
  public synchronized SubmitResult submitExercise(final String submitter, final String contractId,
      final String choice, final Map<String, String> argument) throws DomatarException
  {
    final Contract current = contracts.get(contractId);

    if (current == null)
      throw new DomatarException("Unknown contract: " + contractId);
    if (submitter == null
        || (!current.signatories.contains(submitter) && !current.observers.contains(submitter)))
      throw new DomatarException("Submitter cannot see the contract");

    final TemplateDesc t = getTemplate(current.templateId);
    final ChoiceDesc ch = findChoice(t, choice);

    if (ch == null)
      throw new DomatarException("Unknown choice: " + choice);

    for (final String field : ch.controllerFields)
    {
      final String value = current.payload.get(field);

      if (value == null || !value.equals(submitter))
        throw new DomatarException("Submitter is not a controller of " + choice);
    }

    final Map<String, String> args = argument != null ? argument : Collections.emptyMap();

    if ("Transfer".equals(choice) && current.payload.containsKey("Owner"))
    {
      final String newOwner = args.get("NewOwner");

      if (newOwner == null || newOwner.isEmpty())
        throw new DomatarException("Transfer requires NewOwner");
    }

    final List<String> archived = new ArrayList<>();
    final List<Contract> created = new ArrayList<>();

    if (ch.consuming)
    {
      contracts.remove(contractId);
      archived.add(contractId);
    }

    if ("Transfer".equals(choice) && current.payload.containsKey("Owner"))
    {
      final String newOwner = args.get("NewOwner");
      final Map<String, String> nextPayload = new LinkedHashMap<>(current.payload);

      nextPayload.put("Owner", newOwner);
      final String id = "cid-" + nextId++;
      final Contract successor = new Contract(id, current.templateId, nextPayload,
          partiesFrom(t.signatoryFields, nextPayload),
          partiesFrom(t.observerFields, nextPayload));

      contracts.put(id, successor);
      created.add(successor);
    }

    save();
    return new SubmitResult(archived, created);
  }

  /**
   * Workshop operator lookup. Not on {@link CantonClient}: no party
   * filter, so the mock-network page can load a row by ContractId.
   */
  public synchronized Contract getContract(final String contractId)
  {
    if (contractId == null || contractId.isEmpty())
      return null;
    return contracts.get(contractId);
  }

  /**
   * In-place payload merge on the same ContractId. Signatories,
   * observers, and template stay put. Only keys already on the
   * payload are updated, then the JSON store is written.
   */
  public synchronized Contract patchPayload(final String contractId,
      final Map<String, String> fields) throws DomatarException
  {
    final Contract current = contracts.get(contractId);

    if (current == null)
      throw new DomatarException("Unknown contract: " + contractId);

    if (fields == null || fields.isEmpty())
      return current;

    final Map<String, String> next = new LinkedHashMap<>(current.payload);

    for (final Map.Entry<String, String> e : fields.entrySet())
    {
      final String key = e.getKey();

      if (key == null || key.isEmpty() || !next.containsKey(key))
        continue;
      next.put(key, e.getValue() != null ? e.getValue() : "");
    }

    final Contract updated = new Contract(current.contractId, current.templateId,
        next, current.signatories, current.observers);

    contracts.put(contractId, updated);
    save();
    return updated;
  }

  private static ChoiceDesc findChoice(final TemplateDesc t, final String choice)
  {
    if (choice == null)
      return null;
    for (final ChoiceDesc ch : t.choices)
    {
      if (choice.equals(ch.name))
        return ch;
    }
    return null;
  }

  private static List<String> partiesFrom(final List<String> fields, final Map<String, String> payload)
  {
    final List<String> out = new ArrayList<>();

    for (final String field : fields)
    {
      final String value = payload.get(field);

      if (value != null && !value.isEmpty())
        out.add(value);
    }
    return out;
  }

  private void load() throws DomatarException
  {
    final String json;

    try
    {
      json = Files.readString(store.toPath(), StandardCharsets.UTF_8);
    }
    catch (final Exception e)
    {
      throw new DomatarException("Cannot read mock store " + store, e);
    }

    final JsonMap root;

    try
    {
      root = Json.parseMap(json);
    }
    catch (final DomatarException e)
    {
      throw new DomatarException("Corrupt mock store " + store, e);
    }
    catch (final Exception e)
    {
      throw new DomatarException("Corrupt mock store " + store, e);
    }

    final Number next = root.getNumber("nextId");

    nextId = (next != null) ? next.intValue() : 1;
    contracts.clear();
    final JsonList rows = root.getList("contracts");

    if (rows == null)
      return;

    for (int i = 0; i < rows.size(); i++)
    {
      final JsonMap row = rows.getMap(i);
      final Map<String, String> payload = new LinkedHashMap<>();
      final JsonMap payloadMap = row.getMap("payload");

      if (payloadMap != null)
      {
        for (final Map.Entry<String, Object> e : payloadMap.entrySet())
        {
          if (e.getValue() != null)
            payload.put(e.getKey(), String.valueOf(e.getValue()));
        }
      }
      final Contract c = new Contract(
          row.getString("contractId"),
          row.getString("templateId"),
          payload,
          stringList(row.getList("signatories")),
          stringList(row.getList("observers")));

      contracts.put(c.contractId, c);
    }
  }

  private static List<String> stringList(final JsonList list)
  {
    final List<String> out = new ArrayList<>();

    if (list == null)
      return out;
    for (int i = 0; i < list.size(); i++)
    {
      final String s = list.getString(i);

      if (s != null)
        out.add(s);
    }
    return out;
  }

  private void save() throws DomatarException
  {
    if (store == null)
      return;

    final JsonHashMap root = new JsonHashMap();
    final JsonList rows = new JsonArrayList();

    root.put("nextId", Integer.valueOf(nextId));
    for (final Contract c : contracts.values())
    {
      final JsonHashMap row = new JsonHashMap();
      final JsonHashMap payload = new JsonHashMap();
      final JsonList signatories = new JsonArrayList();
      final JsonList observers = new JsonArrayList();

      row.put("contractId", c.contractId);
      row.put("templateId", c.templateId);
      for (final Map.Entry<String, String> e : c.payload.entrySet())
        payload.put(e.getKey(), e.getValue());
      row.put("payload", payload);
      for (final String p : c.signatories)
        signatories.add(p);
      for (final String p : c.observers)
        observers.add(p);
      row.put("signatories", signatories);
      row.put("observers", observers);
      rows.add(row);
    }
    root.put("contracts", rows);

    final File parent = store.getParentFile();

    if (parent != null && !parent.exists() && !parent.mkdirs())
      throw new DomatarException("Cannot create mock store directory " + parent);

    final File tmp = new File(store.getPath() + ".tmp");

    try
    {
      Files.writeString(tmp.toPath(), Json.toJson(root), StandardCharsets.UTF_8);
      Files.move(tmp.toPath(), store.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    catch (final Exception e)
    {
      throw new DomatarException("Cannot write mock store " + store, e);
    }
  }
}
