# Specifications

These documents are the living **how**: objects, hosts, messages,
identity, and the apps. Present-tense text is the system as built.
**Direction** sections are future work.

If you want the argument first — why an ownership internet instead of
a storefront internet — read [Why Domatar](Why-Domatar.md). Then come
back here for the specs.

Living specs live under [`spec/`](spec/Domatar.md).

## Understand the platform

Read in this order. Stop after the umbrella PART 1–6 if you only need
the vocabulary.

- [Domatar Overview](spec/Domatar.md) — objects, hosts, messages, dispatch
- [Identifiers](spec/platform/Identifiers.md) — ownId, actId, usrId
- [Login protocol](spec/apps/Login-Protocol.md) — federated identity (the protocol)
- [Security](spec/platform/Security.md) — delegations, origin signatures, the wire
- [Platform App](spec/platform/Platform-App.md) — the platform's own application (`domatar`)
- [Installation](spec/install/Installation.md) — the three-level installation model

## Shells (sign-in, home screen, object graph)

- [Login](spec/apps/Login.md) — federated identity (the user app)
- [Desktop](spec/apps/Desktop.md) — the home-screen launcher
- [Navigator](spec/apps/Navigator.md) — the object-graph browser

## Write or change an app

- [Writing Apps](spec/apps/Writing-Apps.md) — how to write a Domatar app
- [Class](spec/platform/Class.md) — class-descriptor objects (implementation, policy, presentation)
- [Service](spec/platform/Service.md) — service objects (the shared, immutable interface layer)
- [LLM Messages](spec/platform/LLM-Messages.md) — LLM-native operations on a class
- [Code style](spec/platform/CodeStyle.md) — Java conventions for this tree

Worked product examples (same pattern; read one):

- [Quippin](spec/apps/Quippin.md) — the social application family
- [Bookstore](spec/apps/Bookstore.md) — a marketplace + library app
- [Spreadsheet](spec/apps/Spreadsheet.md) — cells and formulas
- [Money](spec/apps/Money.md) — accounts, profile, bank
- [AI Agent](spec/apps/AIAgent.md) — LLM-backed conversations

## Marketplace, foreign hosts, site policy

Read these when that problem shows up, not on the default path.

- [App Store](spec/apps/AppStore.md) — offers, InstallApp, portable hosts
- [Foreign Provider](spec/install/Foreign-Provider.md) — host an app where the user has no login
- [Provider customization](spec/install/Provider-Customization.md) — default apps for new accounts
- [Icons](spec/platform/Icons.md) — icon storage and cross-server URLs
