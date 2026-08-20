# Why Domatar

### Why Domatar is the natural successor to the storefront web

This document is the argument: why an ownership internet instead of a
storefront internet. It is not a specification. For how Domatar is
specified — objects, hosts, messages, identity, and the apps — start
at [Specification](Specification.md).

## **PART 1 — THE STOREFRONT INTERNET AND ITS DISCONTENTS**

When you use a service on the internet today, what you are really doing is sending your data to a server that belongs to someone else. They keep your data, file it, index it, and decide what you are allowed to do with it. Your posts live in their database. Your photos live in their cloud. Your messages, your purchase history, your social graph, your documents — all of it sits inside the four walls of someone else's building, under their lock, governed by their terms of service, monetized by their business model, and deletable at their discretion.

This is the storefront model, and it has three structural consequences that no amount of feature work inside any single storefront can fix:

- **You do not own your data**
- **Applications do not interoperate**
- **The provider is forced into the role of publisher**

Every recurring crisis of the internet — surveillance business models, walled gardens, the deplatforming wars, the impossibility of moving your life from one service to another, the helplessness of users in the face of an algorithm — is a symptom of this one root structure. The storefront model put the user on the wrong side of the counter.

Domatar moves the user to the other side of the counter. It is an ownership internet rather than a storefront internet, and the rest of this document explains what that means and why it changes everything.

## **PART 2 — THE DOMATAR ARCHITECTURE IN BRIEF**

Before the argument proper, a short glossary. Domatar is built from a handful of concepts, and the rest of this document leans on them.

- **Object.** The fundamental unit of everything. Every thing a user sees or owns — a microblog post, a bank account, a spreadsheet, a conversation with an AI — is an object: an addressable thing with persistent state, a type, and an owner. There is nothing in Domatar that is not an object, and every object is reached in the same way. Objects contain data, behavior, and links to other objects.

- **Address.** Every object has a single, uniform address. Conceptually the address names four things: the host the object lives on, the application it belongs to, the account that owns it, and the object's own identifier. Crucially, the form of the address makes no distinction between "my object" and "a stranger's object on the far side of the world" — they are the same kind of address, differing only in their coordinates.

- **Owner (account).** Every object is owned by an account — an identity that belongs to a person, role, or entity. The account is the unit of ownership: when we say "you own your data," we mean your account is the owner recorded on the objects. One sign-in establishes that identity everywhere on the network.

- **Host.** A host is an abstract location where objects live — not a physical machine. It is a name. Each host is mapped, through a network directory, to whatever physical provider currently serves it. This indirection is load-bearing: because a host is an abstract name and not a server, the physical provider behind a host can change while the host name — and therefore every address that refers to its objects — stays exactly the same. To a reader or another application, an object whose host moves to a new provider has not moved at all: its address is unchanged and it answers messages exactly as before.

- **Provider.** A provider is the physical server that actually runs the platform and serves one or more hosts. "Renting hosting" means renting space on a provider to serve your host; "self-hosting" means being your own provider. Providers are custody; accounts are ownership; the two are independent.

- **Application.** An application is the unit of development, distribution, and definition. Every object belongs to exactly one application and carries that application's identifier. An application defines the types of objects it introduces and the behaviors attached to them. An application is not an addressable thing at runtime. You never send a message "to an app"; you send messages to objects, and the application is simply the namespace and authorship the objects were born under.

- **Class and service.** A class is an object's implementation (handler, icon, instance conventions, policy). The interface — attributes and messages — lives on a service. GetCls returns those merged, self-describing, so a human (in a graph browser) and an AI agent can read at runtime what the object can do. This is the basis of both interoperability and agent-friendliness.

- **Message.** Every interaction in Domatar — a click in a browser, a call from one object to another, a request between two servers on different continents — is a message to an object. The platform resolves where the target lives and delivers it, locally or across the network, by the same mechanism. There is exactly one way to do anything: send a message to an object.

- **Authorization.** Each object decides, for each message, whether the caller is permitted to perform that operation, and the decision is made where the object lives. Permission is therefore a property of the object and its owner, not of the company whose machine happens to store it.

The whole of Domatar is these pieces: owned objects, reached by uniform addresses, typed by self-describing classes, living on abstract hosts that are served by physical providers, manipulated only by messages, and guarded per-object. Every advantage in this document is a consequence of this small set of choices.

## **PART 3 — THE DOMATAR INVERSION: YOU OWN YOUR DATA AND YOUR APPS**

Domatar is a general, distributed, interoperable application environment. It is "general" because it has no built-in knowledge of any particular application, and because an application is a unit of authorship rather than a runtime entity: the protocol itself speaks only of objects, classes, hosts, and messages. Everything a user sees — a microblog post, a bank account, a spreadsheet cell, a conversation with an AI — is an object with persistent state and an owner. Applications are just sets of objects, and the data and the applications are the property of the people who run and use them.

Domatar is a protocol, not a platform. It mandates no language and no database; a system "supports Domatar" by exposing its interface as Domatar objects. In that sense Domatar requires no new technology — it is a new agreement about how existing software presents itself. Unlike most protocols, which are about passing data, Domatar is about identities: an object is a thing that persists and that has data, behavior, and links to other objects. That is already how most applications are conceived — as collections of conceptual things — so presenting them as Domatar objects is a natural move, and often an easy one. The right analogy is the World Wide Web. The Web is just a protocol that any software can implement, yet it creates something that feels tangible: a web of documents. Domatar does for applications what the Web did for documents — it creates a web of living objects.

In Domatar, every object carries the identity of its owner and lives on a host. In the storefront world, "where your data lives" and "who controls it" are the same thing. In Domatar they are different. The owner of an object is an account. The host is an abstract place — served by some physical provider — that stores and serves the object. Because ownership and hosting are distinct, a user can own things that are stored in many places, and a place can store things owned by many users, without either fact compromising the other.

Domatar's architecture enables you to own your data even when that data is sitting inside another user's application. It is why you can pick up your whole digital life and move it to a different provider without anyone else noticing. It is why applications written by strangers can cooperate. And it is why an AI agent can act on your behalf across the entire network as if it were you, because "acting as you" is a well-defined, enforceable thing rather than a fiction maintained by one company's servers.

## **PART 4 — THE OWNERSHIP SPECTRUM: FROM SELF-HOSTING TO RENTING**

Ownership in Domatar is not all-or-nothing, and the choice of where on the spectrum you sit is made per object. In practice the grain is usually per application: you might run a server of your own for the apps you care most about, let the devices you already own host theirs, and rent hosting for the rest — all under the same identity. A user can therefore occupy several points on the spectrum at once, and move any object (or any app's objects) along it later, without changing how anything works.

### **4.1 The extreme case: own everything, literally and physically**

A user can run their own server. In that case they own everything in the fullest possible sense: the hardware, the operating system, the storage, the running platform, the applications installed on it, and every object those applications create. Nothing about their digital existence depends on any company's continued goodwill, solvency, or terms of service. This is the sovereign extreme, and it is a first-class, fully supported mode — not a degraded fallback. A self-hosting user is indistinguishable, to the rest of the network, from any other participant; their server is simply a provider that happens to serve its owner's host.

### **4.2 The everyday case: the devices you already own**

Self-hosting sounds like an enthusiast's pursuit, but most people will do it without ever thinking of themselves as self-hosters, because the things they already own are computers. The Internet of Things is where self-hosting becomes mainstream.

Your car is a powerful computer with persistent software and state. Today that state — its settings, its trip and maintenance history, its telemetry — is siloed in the manufacturer's cloud and owned by the manufacturer. In Domatar the car is a provider, and the car's objects live in your account, owned by you. Your car is just another app in your account. Your spreadsheet can read the odometer; your agent can book its service. None of this is new engineering — the car already runs software and stores state. Domatar only asks it to expose that state as objects instead of hiding it behind a proprietary app.

In Domatar your phone is simply another provider that you own. Your smartphone can display objects it hosts locally just as easily as objects hosted anywhere else on the internet.

### **4.3 The expected typical case: rent hosting from a third party**

Most people will not run their own servers, just as most people do not generate their own electricity. They will rent hosting from third-party providers — hosting companies whose business is to run reliable, fast, backed-up Domatar servers and rent space on them. This is the ordinary case, because renting hosting in Domatar is fundamentally different from "having an account" in the storefront model.

When you rent hosting, the provider stores your objects, but you still own them. The objects carry your identity, not the provider's. If you leave, you take them with you — not a snapshot export, but the authoritative objects themselves, which can be moved to another provider or to your own machine. The provider is your landlord, not your owner. They rent you space; they do not acquire your belongings by storing them.

Within the rented-hosting case, there is a further choice about the applications themselves:

- **Own your own copies of the apps.** A user can install their own private copy of each application onto their account. The application's code is present for them, its objects are theirs, and their use of it is independent of anyone else's. This is the natural default for anything personal.

- **Share apps with other users on the same hosting site.** Alternatively, users on the same provider can share a single installed application — one running copy serving many users, each owning their own objects within it. This is efficient for the provider and convenient for users, and crucially it does not compromise ownership: shared application, separately owned data.

The point of the spectrum is that the user chooses their own trade-off between convenience and sovereignty, and can revise that choice later, because ownership of the data is constant across the whole range. The storefront internet offers exactly one point on this spectrum — "you have an account, we own everything" — and no exits.

## **PART 5 — OWNING YOUR DATA INSIDE OTHER PEOPLE'S APPLICATIONS**

Perhaps the most counter-intuitive property of Domatar is that you can own your data even when it lives inside an application that belongs to, and is hosted by, someone else. This is a property that has no equivalent at all in the storefront world, and it deserves to be stated plainly because it sounds impossible.

In the storefront model, if your money is "at" a bank's website, the bank owns the database row that says you have money. If your comment is "on" a social network, the network owns the comment. Possession and ownership are fused: whoever holds the data owns it.

In Domatar they are not fused. An object has a host (where it physically lives) and an owner (whose identity it carries), and these can point at two different entities. A bank can hold an account object on the bank's own server — under the bank's lock, served by the bank's machine — while that account object is owned by the customer. The bank has the data in its custody; the customer has it in their ownership. The platform enforces this distinction at every access: the customer can read their account because they own it; the bank can operate on it because it hosts it; a stranger can do neither. Authorization is decided object by object, at the place the object lives, by rules the application declares — not by which company's building the bytes happen to sit in.

This is what makes Domatar an ownership-oriented internet rather than merely a "bring your own server" internet. Even when you are a guest in someone else's application, your things remain yours.

## **PART 6 — THE PLATFORM / PUBLISHER PROBLEM AND FREEDOM OF SPEECH**

The fiercest political fight on the internet is the platform/publisher problem. A publisher is responsible for what it publishes — it chooses, edits, and stands behind content, and may be held liable for it. A platform is a neutral conduit — it carries other people's content and is not treated as the author of it. The crisis of the storefront internet is that the dominant services are structurally both at once and can be cast as either at will: they host everyone's speech (platform) but also rank, recommend, demote, and delete it (publisher). Because they own the data and the distribution, they cannot escape the publisher's responsibilities, and so they are forced — by liability, by politics, by their own business interest — to police speech. Whoever owns the printing press decides what gets printed.

Domatar solves this problem by giving every user their own printing press. Your posts are your objects, owned by you, authored by you, published by you from your own host (or your rented space on a host). You are your own publisher. You bear the responsibilities of a publisher for your own speech, which is exactly where the responsibility belongs.

The hosting company, meanwhile, is unambiguously a platform. It rents out servers; it does not own, author, choose, or rank the content those servers carry. It is far more like the electric company or the telephone network — a neutral carrier of bits it does not own — than like a newspaper. The storefront internet blurred the line because one company occupied both roles. Domatar draws the line cleanly: the user is the publisher, the host is the platform, and the two are different parties by construction.

The consequence for freedom of speech is direct. In the storefront model, losing your account means losing your speech, your audience, and your archive in one stroke, at the discretion of a company that owns all three. In Domatar, if a hosting provider no longer wishes to host you, you can move your host to another provider and take your objects, your identity, and your audience relationships with you; and because a host is an abstract name rather than a server (PART 2), the move is invisible to everyone else — to any reader or application, nothing changes, because the addresses that point at your objects are exactly the same as before. Deplatforming, in the catastrophic sense the word has acquired, is not architecturally available.

## **PART 7 — AD-HOC INTEROPERABILITY**

Domatar has a notion of application (PART 2) — but it draws no runtime boundary around one. Interoperability is the direct result of the uniform object model: every object, whatever application, host, or user it belongs to, is addressed the same way, queried the same way, and asked to describe itself the same way, and the platform makes no distinction between objects of the same application, host, or user and objects of different ones.

An application therefore need not be a sealed silo — though it may choose to be. An app's objects grant or withhold access through their own permission rules (PART 2, Authorization), so an application that wants to keep its objects private simply declines to grant rights to outsiders. Siloing is a choice an app makes with authorization, not a wall the model imposes.

This makes interoperability the default rather than a feature. Two applications written by two strangers who never spoke can cooperate, because each can discover the other's objects and operations through the same universal interface, with no prior bilateral arrangement. A spreadsheet can pull a live price out of a bookstore listing owned by a different user on a different server. A microblog post can reference an object from a banking application. The graph of objects is a single connected fabric across applications, users, and servers, not a set of disconnected islands joined by hand-built bridges.

Crucially, an application can implement a service defined by another application — sharing that interface as part of its own contract — so interoperability extends down to the level of shared schemas, not just shared messages. New applications interoperate with old ones the day they are published, because they all speak the one object protocol. Anyone may publish an application; anyone may run a host; anyone may sign up — and everything composes.

## **PART 8 — DISTRIBUTED APPLICATIONS FOR FREE**

The same architecture that lets different applications interoperate also lets different instances of the same application interoperate — which is to say, Domatar applications are naturally distributed.

In the storefront model, an application is one company's central deployment. "Distributed" means that one company runs many servers behind one logo; it is still a single owner, a single silo. Federated alternatives exist, but federation there is a hard-won, application-specific protocol that each app must design, implement, and maintain.

In Domatar, distribution is inherent because addressing is uniform across hosts. An object on your server and an object on a stranger's server on the far side of the network are reached by the same mechanism; the platform resolves where the target lives and routes the message there, whether that is the same machine or a different continent. An application therefore does not have to be deployed in one place to behave as one system. Many independent installations of the same application form one distributed application simply by sending each other ordinary messages. There is no central instance, no home server, no single point of control or failure. The microblog is not a site; it is a behavior that emerges from thousands of users' own copies talking to each other. The bank is not a company's mainframe; it is a pattern that any user can instantiate and that interoperates with every other instantiation.

Distributed-by-default and interoperable-by-default are the same property seen from two angles: the uniform, location-transparent, self-describing object interface. Get that one thing right and both fall out for free.

## **PART 9 — THE NATURAL HOME FOR AI AGENTS**

Domatar is the ideal platform for AI agents, and not by coincidence — the properties that make it good for people make it spectacular for agents.

An AI agent's central difficulty on today's internet is that every service is different. Each has its own API, its own auth, its own data model, its own quirks, its own documentation written for humans. An agent must be taught each one individually, and the moment a service changes, the agent breaks. The storefront internet is, from an agent's point of view, a thousand custom-made locks with a thousand different keys.

Domatar presents the agent with one lock and one key. Because the platform is general, every application — present and future — is reached through the same uniform object-message interface. Because every object is self-describing, the agent can inspect any object of any application, at runtime, and learn what that object is and what operations it offers, in a single universal format. The agent does not need a pre-built integration for each app; it discovers capabilities by looking, the same way a person browsing the object graph would, except instantly and at scale.

Three deeper properties make Domatar the agent platform:

- **The agent's reach is exactly the user's reach.** The agent is an app within the user’s account, carrying the user's identity. Every server it touches verifies that identity for itself and applies its own per-object permission rules. The agent has no special powers and no bypass: it can do precisely what its owner could do by hand, no more and no less. This is the only safety model that scales to a world-wide network, because it requires no one to "trust the agent" — they only ever trust the user, exactly as they already do.

- **Memory is just the object graph.** The agent's history — its conversations, the operations it performed, the results it got — are ordinary owned objects, persisted like everything else. Long-term memory that a language model cannot hold across sessions becomes a simple query over the user's own data, and it travels with the user when they move hosts.

- **Uniformity compounds with scale.** In a future where AI is trained on Domatar itself, the payoff multiplies. Today an AI must learn each service's idiosyncrasies; in a Domatar-native future, an AI that has learned the one protocol — how objects describe themselves, how messages are addressed, how ownership and permission work — has thereby learned how to operate every application that exists or will ever exist on the network, including yours and including the apps of the people you interact with. The agent walks into a stranger's bank or marketplace or microblog it has never seen and knows what to do, because there is only ever one thing to know.

This is why an agent on Domatar can do for your whole digital life what today's agents can barely do for a single app: the network was built, from the ground up, to be inspected, understood, and acted upon by something that reads a uniform, self-describing, ownership-aware interface.

## **PART 10 — FOUR APPLICATIONS, CONCEPTUALLY**

To make the preceding abstractions concrete, here are four very different applications as they exist on Domatar. Each illustrates a different kind of application: content and ownership (Quippin), property and the transfer of title (Money), ad-hoc interoperability (Spreadsheet), and delegation to an agent that builds on all three (AI Agent).

### **10.1 Quippin — microblogging, where everyone owns their own quips**

Quippin is a social microblog: short posts ("quips"), a following graph, a news feed, reactions, and the social machinery one expects. What makes it a Domatar application rather than a storefront is where the posts live and who owns them.

Each user's quips are objects on that user's host, owned by that user. There is no central "Quippin database" holding everyone's posts. When you write a quip, you create an object in your own account; it is yours, it lives where you put it, and you are its publisher. Following another user is simply a relationship recorded between objects; your news feed is assembled by asking the people you follow — on their own hosts — for their recent quips, and gathering the answers. The feed is a momentary aggregation across many independently owned, independently hosted collections, not a slice of one company's table.

The consequences map directly onto the earlier parts:

- **Ownership (PART 3, PART 5).** You own every quip you have ever written. No one can lose your archive but you, and you can carry it to a new host intact.

- **Freedom of speech (PART 6).** You are your own publisher. A host can decline to keep renting you space, but it cannot delete your speech from the network, because the host never owned it. Your followers' links point at you — your identity and your abstract host — not at a row in some company's database, so they keep working automatically and follow you to a new hosting service if you move.

- **Distribution (PART 8).** Quippin is not a site; it is the emergent behavior of every user's own copy of the microblog, talking to every other. The network has no center to capture, censor, or switch off.

- **Interoperability (PART 7).** A quip is an ordinary object, so a spreadsheet can cite it, an agent can read it, and another application can reference it — without Quippin having to expose a special API.

Quippin demonstrates the "content you author" kind of application, and the ownership benefit is the obvious and emotionally direct one: your words are yours.

### **10.2 Money — assets, where you don't transfer money, you transfer title**

The Money application models banking, but its real subject is assets of any kind — currency, shares in a company, units of a fund, anything whose essence is "a thing of value that belongs to someone." The application is called "Money" because, no matter what kind of asset backs it, it can be used as a medium of exchange, without the throughput problems of Bitcoin. The microblog illustrated content; Money illustrates property, and it leans on the ownership/custody split of PART 5 as its central mechanism.

An account is an object that records a balance. The decisive design choice is that an account lives on the bank's host but is owned by the customer. The bank holds it in custody — serves it, secures it, operates on it under the bank's rules — while the customer is its true owner and can read it precisely because they own it. The same enforcement that protects a quip protects an account: access is decided at the object, where it lives, by the rules the application declares. The customer cannot be quietly written out of their own holdings, because their ownership is a property of the object, not a courtesy of the database administrator.

This reframes what a payment is. In the storefront world, "transferring money" is one institution decrementing a number it owns and another institution incrementing a number it owns, with the customer trusting both ledgers. In the Money app, value never leaves the bank and is never "sent" as a free-floating quantity. What changes hands is title: the ownership of an asset object moves from one party to another. Paying someone is transferring ownership of a thing of value, not copying a number across a counter. Transferring assets from one bank to another, perhaps involving an exchange rate, is done within a user's account.

Because an asset object lives in the institution that issues it (the bank, the company, the fund) while being owned by its holder, a change of owner happens within one institution rather than between two. This is precisely the hard problem that blockchains were invented to solve: letting an asset change hands without a trusted central operator and without double-spending. Domatar solves the same problem, but in a way blockchains cannot match on scale. A blockchain forces every transfer in the world through one shared global ledger, so total throughput is permanently capped no matter how much demand grows. In Domatar each institution settles its own assets independently, so the number of institutions can grow without limit and total throughput grows right along with them — two unrelated transfers at two different institutions never contend for the same ledger.

### **10.3 Spreadsheet — interoperability, where any cell can cite any object**

The Spreadsheet application is, on the surface, the most ordinary thing imaginable: named grids of cells holding numbers, text, and formulas. A formula can do arithmetic and refer to other cells in the same sheet, just as in any spreadsheet. The microblog illustrated content and Money illustrated property; Spreadsheet illustrates interoperability (PART 7), and it does so with one small feature that has no equivalent in an ordinary spreadsheet.

A cell's formula may reference, not just another cell, but any object anywhere on the network, by citing that object's address and the attribute it wants. Written out, a cell can say "the Title of that bookstore listing," or "the Text of that quip," or "the Balance of that account" — and the spreadsheet fetches the live value, on demand, from wherever that object lives. The referenced object can belong to a different application, be owned by a different user, and sit on a different server on the other side of the world. None of that matters to the formula, because in Domatar every object is reached by the same uniform address and answers the same way (PART 2).

This is ad-hoc interoperability, and three properties of it are worth noting:

- **No prior arrangement.** The Spreadsheet app was not built with any knowledge of the Bookstore, of Quippin, or of any other app. Its authors never struck a deal, published a connector, or agreed on a schema with anyone. Yet a cell can pull a book's price out of a marketplace listing and a population figure out of a microblog post in the same sheet, because both are just objects with attributes, and reading an attribute is one universal operation (PART 7).

- **Live, not copied.** The cell does not hold a stale paste of the other object's data; it holds a reference, and re-evaluating the sheet fetches the current value. The spreadsheet composes other people's living objects into a new view without owning, copying, or locking them.

- **Ownership and permission still hold.** The fetch is performed as the user who owns the spreadsheet, and the object being read applies its own authorization where it lives (PART 5). If the user is allowed to read that listing or that quip, the value appears; if not, the cell simply reports that the value is unavailable. Interoperability does not punch a hole in ownership — it travels through the same permission model as everything else.

Spreadsheet demonstrates the "compose other people's data" kind of application. The benefit here is not primarily about owning your own data (though your sheets are of course your own objects); it is that the uniform, self-describing object model turns the entire network into one addressable data source that any application can draw on, safely and without permission-seeking, the moment it is published.

### **10.4 AI Agent — delegation, where the agent simply knows what to do**

The AI Agent is the application that lets a user hand a request, in plain language, to a language model that has working access to the user's own object graph and — with permission — to the objects of other users across the network. Quippin was content, Money was property, and Spreadsheet was interoperability; the AI Agent is delegation, and it is best understood as the generalization of the Spreadsheet's use case. Where a spreadsheet cell reaches across the network to read one named attribute of one object, the agent reaches across the network to invoke any operation of any object — and where the spreadsheet's author had to hand-write each reference, the agent discovers what to read and what to call by itself, at runtime. It showcases the agent properties of PART 9.

Conceptually it works like this. The user states a goal. The agent, acting strictly as the user, looks at what applications and objects the user has. For any application it encounters, it reads that application's self-description to learn what operations are available, then performs the appropriate ones — on the user's own apps and, where the conversation has been pointed at others, on theirs. The platform routes and authorizes every one of those operations exactly as if the user had performed it by hand: each host the agent reaches verifies the user's identity for itself and applies its own per-object rules. The agent inherits the user's reach and nothing more.

Every part of this is borrowed wholesale from the protocol, which is why the agent is small and the result is powerful:

- It can act across every application without explicit integration, because all applications are reached uniformly and describe themselves (PART 7, PART 9) — the very same property that lets a spreadsheet cell cite any object (10.3), now exercised dynamically and over every operation rather than just attribute reads.

- It is safe at world scale without anyone trusting "the AI," because its permissions are precisely the user's permissions, enforced where the data lives (PART 5, PART 9).

- Its conversations and actions are themselves owned objects, so the user's agent history is the user's property and moves with them (PART 3).

- In a future where AI is trained on Domatar, the agent walks into an application or a stranger's object it has never seen and knows what to do — because the network has a uniform interface, and the agent already knows it (PART 9).

The benefit of ownership, for the agent, is that delegation finally becomes trustworthy. You are not granting a third-party service sweeping access to your accounts; you are letting a tool act as you, within the exact bounds the network already enforces for you, over data you already own.

There is also a large practical benefit, beyond trust. In the storefront model, users have no clear picture of what data they own or what access they are granting, and every app must build its own separate machinery for managing permissions — a consent screen here, a sharing setting there, an API-token panel somewhere else. Keeping track of all of it is a nightmare, both for the user and for the apps. In Domatar there is nothing to wire up: all of a user's data and applications already exist as owned objects in the user's own account, and the agent operates on them directly under the one uniform permission model that the platform already enforces. Granting or withholding access is the same act for every app, because there is only one kind of object and one kind of permission.

## **PART 11 — DOMATAR VS. THE ALTERNATIVES**

Domatar is not alone in rejecting the storefront model; there is an active field of projects attacking these problems. This part proceeds in four steps. First, problem by problem, against the best partial solution to each. Then it sorts the serious substrate rivals by a single question that turns out to explain almost everything about them — who the product is really for: people who build applications, or people who create documents and content. Then it looks at the most crowded arena of the moment, the layer of frameworks connecting AI agents to today's services — a different kind of rival, built on top of the storefront rather than offered as an alternative to it. Finally it turns to a dimension the others neglect, the friendliness of the model.

### **11.1 Problem by problem**

**Owning your data.** Storefronts give you an account and a "download your data" button; the authoritative copy stays with the provider and the export is a dead snapshot. Personal clouds and self-hosted suites give you custody but strand your data, unable to participate in anyone else's apps. Solid (the personal-data-"Pod" project) comes closest in spirit — your data in a store you control, apps granted scoped access — and on this single axis Solid and Domatar agree. Domatar goes further by making ownership a property of each object, enforced wherever the object travels, with no authoritative copy held by anyone but you.

**Owning your data inside other people's applications.** Storefronts make this impossible by construction: possession is ownership. Blockchains let an asset be owned independently of any single operator but force everything onto a public global ledger, with throughput, privacy, and cost penalties and no model for ordinary private data. Solid is again the nearest relative: your data stays in your Pod while another party's app uses it. Domatar's difference is granularity and uniformity — ownership and custody split at the level of the object, so your account object can live on a bank's host while belonging to you, with per-object authorization enforced where it sits, private by default, no global ledger, and throughput that grows with the number of institutions rather than being capped by one chain.

**The platform/publisher problem and free speech.** Storefronts fuse press and distribution, are cast as publisher, and are forced to police speech. Federated social networks (the Fediverse / ActivityPub) are a real improvement — many servers, no single owner — but each instance still owns and hosts its users' speech, so the fusion reappears per instance, and moving instances loses your history and graph. Nostr and Bluesky's AT Protocol go further on portability: a Nostr user is a keypair, and Bluesky identities are portable DIDs, so speech and audience can survive leaving a relay or server. Domatar reaches the same destination from the ownership side: the user is the publisher and owns the speech, the host is a neutral platform that owns nothing it carries, and because a host is an abstract name (PART 2) the audience follows you automatically when you move.

**Application interoperability.** Storefront APIs are guarded moats; open API standards and integration platforms are useful glue, but every integration is built and maintained pairwise against APIs that shift underneath it. Domatar offers one uniform, self-describing object interface for every application, so interoperability is the default, is ad hoc, needs no prior arrangement, and apps can even adopt one another's data types (PART 7).

**Distributed applications.** In the storefront world "distributed" means one company's many servers. Federation protocols (ActivityPub) and agent-centric platforms such as Holochain achieve genuine distribution — Holochain notably without a global ledger, each agent holding their own chain. But each federated app must design its own protocol, and cross-application federation is essentially absent. In Domatar distribution is inherent in uniform, location-transparent addressing, and the very same mechanism federates across different applications as within one (PART 8).

**A platform for AI agents.** Storefronts present a thousand custom-made APIs; an agent needs a custom integration per service, breaks on every change, and must be handed broad credentials. Agent frameworks and tool/plugin standards (such as MCP) help declare tools but sit on top of the same heterogeneous services and the same coarse credential-sharing. Domatar gives the agent one self-describing interface it inspects at runtime — no per-app integration ever — and the agent acts as the user with exactly the user's permissions, enforced where the data lives, so no one need trust "the agent." This is the least-occupied ground of all: the other projects were designed before agents mattered and bolt them on, whereas Domatar's uniform, self-describing model is agent-ready by construction.

### **11.2 The app-builder substrates**

The most revealing way to sort these projects is by the audience they are built for. Some are aimed at app-builders: they are substrates, protocols, or frameworks whose pitch is "build on this." Others are aimed at document-creators: end-user products whose pitch is "create your content here and keep it." The distinction explains both what a project can do and how far it has spread. Domatar is unambiguously aimed at app-builders, so its true peers are the other builders' platforms. There are three groups — the app-builder substrates; the substrates that set out to be platforms but have only succeeded as document-creators; and the pure document-creators — and Domatar belongs with, and should be judged against, the first.

**Solid** carries the most authority of any rival — it is Tim Berners-Lee's project, the inventor of the Web proposing to refit it for data ownership — and it is squarely a builder's platform: developers write apps that operate on data in a "Pod" the user controls, the same instinct as Domatar's ownership/custody split. Two things have held it back. Its substrate is the Linked-Data / RDF stack, powerful and hard in equal measure; and a Pod is only a data store, with application logic running elsewhere, so Solid hands a builder a permissioned filing cabinet rather than a place where applications live. Years of work and a commercial arm (Inrupt) have produced real institutional pilots — government-issued citizen Pods in Flanders, public-sector and media trials — but almost no consumer applications and no consumer audience. Solid is authority and a sound instinct held back by a difficult substrate and a missing application layer.

**Urbit** is the closest to Domatar in ambition: a personal server you own, on a single uniform substrate where applications interoperate and identity is yours. The shared conviction is striking — own your computer, one coherent model, peer to peer. But Urbit pursues it as a clean-slate operating system, with an invented language and runtime and an identity layer built on scarce, tradable address space, and the cost is a famously steep learning curve. In adoption it has stayed marginal — a live network counted in tens of thousands of "ships" rather than millions of users, visible leadership and restructuring upheaval in 2024–2025, and an enthusiast base that never crossed into the mainstream. It is the cautionary case: getting the unified model intellectually right is not enough if ordinary people cannot get in the door. Domatar seeks the same coherence without reinventing the wheel — ordinary objects, owned by ordinary accounts, on ordinary hosts, using ordinary technology.

**Holochain** makes the same anti-blockchain argument Domatar makes for assets — agent-centric, each participant holding their own chain, scaling because there is no global-consensus bottleneck. It is a genuine app-builder framework, but after many years of development it remains largely pre-adoption: few production applications, no broad user base, more compelling as an argument than established as a platform. Freenet — the present-day project of that name, a ground-up redesign led by the original Freenet's creator and unrelated to the older network now called Hyphanet — is the newest close kin to Domatar in spirit, aiming at a general, fully decentralized platform where application state lives in WebAssembly "contracts" and any app, from chat to social feeds, is built on the one substrate. But it is still under active development rather than in real use.

Two more belong in this group by audience, though their pitch is narrower. The local-first libraries — Automerge and Yjs at the foundation, with frameworks such as Jazz and ElectricSQL above them: Yjs is near-ubiquitous inside collaborative editors. But they succeeded at the altitude of document data, not applications. They give a builder conflict-free synchronization of state across devices and collaborators, not an environment in which applications live, interoperate, and enforce ownership; and they have no answer for data that must sit inside an institution, such as a bank balance, where they quietly hand it back to a storefront. Blockchains, finally, are an app-builder substrate that works for one thing — assets owned independently of any operator — but force everything onto a public global ledger, with throughput, privacy, and cost penalties that make them unfit as a general home for ordinary private data and applications.

### **11.3 Substrates that became apps**

Each of these set out as an app-builder's platform — a protocol on which many kinds of decentralized application could be built — yet every scrap of their real-world success has come as a single document-creator product, almost always social. The reason is the same in each case: each was, underneath, a protocol scoped to one domain — the broadcasting of public content — and that scoping is at once why it found a real audience there and why it cannot generalize beyond it. The specialization that made it usable made it un-generalizable.

**AT Protocol**, the engine beneath Bluesky, is the clearest example, and as a product it is the most successful project on this entire list: on the order of forty million registered accounts and tens of millions monthly active. It also delivers real portability — DID-based identity and personal data servers let you change host and keep your followers, exactly Domatar's abstract-host promise. But that success is entirely Bluesky the microblog. As a builder's platform the protocol is in practice tuned for the microblog — its record types, relays, feed generators, and app-views are a social-media architecture — and almost nothing of consequence has been built on it that is not a variation on social media. It is a social protocol wearing the clothes of a general one.

**Nostr** tells the same story in miniature. In principle it is a general protocol — signed events on relays, where an "event kind" could model anything — and a real, committed following grew around it, on the order of a couple of million publishing identities concentrated in the Bitcoin community. But there is no application environment in Nostr: no shared mutable objects with behavior, no computation, only the broadcast of signed messages, so everything actually built on it is a broadcast-shaped social or content app. Its minimalism is both why it works and why it stops there.

**The Fediverse** — Mastodon and the wider ActivityPub world — is the elder of the three, with well over ten million accounts and a durable, genuinely decentralized culture. ActivityPub is a real protocol, and several app shapes ride on it — microblogging, photos, video, link aggregation — but every one is a form of social media, each instance still owns and hosts its users' content, and moving instances loses your history and graph. It generalizes across kinds of social app and no further, and it shows, in passing, why per-instance ownership is not the same as personal ownership.

### **11.4 The document-creators**

The third group never reached for the territory Domatar occupies. These are pure document-creator products — end-user applications whose purpose is to let a person make and keep their own content, with no pretension to being a platform. Anytype, a local-first notes-and-documents app with a real consumer following, is representative: it gives users genuine ownership of their documents, and that is the whole of its ambition. Such products are useful and often well-loved, but they are not rivals to an application substrate; they are the kind of thing that gets built on one.

The pattern across all three groups is consistent. Each rival gets one or two of the properties genuinely right, and several — Bluesky's reach, Yjs's ubiquity, Solid's authority — are well-established. But for every rival, the property it achieves is a feature it set out to build, and the ground it never reached is ground its model could not cover.

Most of these rivals are confined not only to some of the properties but to a band of app types. Holochain and Freenet are made for applications with a social, multi-party shape — chat, email, feeds, shared spaces — and of modest size; they are no home for a private spreadsheet, and no home for an enterprise system of record. AT Protocol and Nostr are narrower still, scoped to public broadcast. The local-first libraries serve collaborative documents and stop there. Blockchains serve trustless assets and little else affordably. In each case the design decision that gives a rival its strength is the same decision that fences it in: that baked-in choice is simultaneously its sweet spot and its cage. Domatar bakes in almost none of those choices — it rides ordinary servers and databases and assumes nothing about whether an app is social or solitary, small or vast, document-shaped or asset-shaped — so the one model carries a personal spreadsheet, a planet-scale microblog, and a bank's ledger of record alike.

Ownership, ownership-without-custody, the publisher/platform split, ad-hoc interoperability, inherent distribution, and an agent-native interface are not six features that Domatar bundles or integrates; they are six use-cases that naturally fall out of a single architecture, because that architecture is simply a better model of the problem space. Model the world as owned objects, addressed uniformly, with custody separated from ownership, and these results appear on their own — not engineered for one by one, but implied by the model, the way a good theory predicts facts that no one built into it. Domatar is not a superior collection of features, but a superior model, from which the features follow.

### **11.5 The agent layer**

The most crowded arena right now is not a storefront alternative at all but a layer built on top of the storefront: the tools that connect AI agents to existing services.

**Tool and plugin protocols** — the Model Context Protocol (MCP) most prominently, alongside function-calling schemes and their emerging variants — let a model discover and call declared tools through a uniform envelope. This is the closest in spirit to Domatar's self-describing objects, and it is a real advance. But the uniformity is skin-deep: behind each tool is still a custom-built service with its own data model and its own authentication, and someone must write and host an MCP server for every service and keep it in step as that service changes. MCP standardizes the question — "what tools do you have?" — without unifying the thousand different answers. In Domatar there is no tool server to write, because every object already describes itself; the agent interface is the object model itself, present for every application that exists or ever will, at no extra cost.

**Orchestration frameworks** — LangChain, LlamaIndex, CrewAI, AutoGen, and their kin — are developer libraries for assembling agent logic: prompts, memory, chains, multi-agent choreography. They organize how an agent thinks, but supply no common ground for what it acts on; each integration underneath is still hand-built against a heterogeneous API.

**Integration hubs** — Zapier, Composio, and the connector vendors such as Merge, Paragon, and StackOne — take the brute-force route, maintaining libraries of pre-built connectors to hundreds of SaaS APIs. This is the storefront problem industrialized rather than solved: a vendor shoulders thousands of pairwise integrations that break when APIs drift, and the agent is still handed broad credentials to each service. Useful glue — but glue is needed only because the surfaces do not fit.

**Computer-use and browser agents** — Operator-style systems that drive a human interface by clicking and typing — are the frankest admission of the underlying problem: where there is no machine interface, the agent is reduced to impersonating a person at a screen, slowly and brittly, with no permission model finer than "logged in as you."

Agent-to-agent protocols such as A2A let agents coordinate with one another, but leave each agent's access to the underlying data exactly as fragmented as before. Enterprise copilots — Microsoft Copilot, Salesforce Agentforce, Glean — are genuinely capable, but only inside one vendor's data perimeter; cross that boundary and the heterogeneity returns.

The common ceiling is that every one of these sits on top of the storefront internet and works to make its heterogeneity bearable — through a tool wrapper, a connector, an orchestration layer, or an impersonated UI — without ever removing it. The agent still faces many data models and many authentication schemes, and still must be trusted with broad credentials to each. Domatar removes the heterogeneity at the root. It offers more than one self-describing object interface for every application: it offers a connected graph of objects whose links lay bare the application's logic and design. A person reading that graph in a browser can see how the application works, and a properly trained agent can read it the same way. Even today, a capable agent can, untrained, understand how an application works, when handed the graph as context. And because the agent acts as the user, with precisely the user's permissions, enforced where the data lives, nothing has to "trust the agent" (PART 9). The agent layer is racing to bridge a gap that, on Domatar, is not there to bridge.

### **11.6 A model people already understand**

There is one more axis, rarely listed among technical advantages, on which Domatar might differ most sharply: the friendliness of the model. Decentralization has a graveyard — PGP, crypto wallets, and arguably Urbit — of systems defeated not by capability but by the mental burden they placed on users. Comprehensibility is not a nicety; it is often the deciding factor in adoption.

The Domatar model is not merely simple; it is already familiar. Everyone who uses a smartphone lives inside it: apps live on my phone, tied to my account; I install and remove them; my data feels like mine; I get a new phone, sign in, and my apps and data come back. That is the Domatar model almost exactly — apps and objects in your account, which travel with you. The personal computer told the same story a generation earlier: programs installed on a machine you own, files on your own disk.

The twist is that on a phone the ownership is half an illusion: when you restore a new device, your posts re-download from the company's servers — the data never truly traveled with you; it was always theirs, and the device merely hid that. Domatar takes the intuition people already hold and makes it real. That is a rare position for a new platform: it need not teach a new mental model, only correct a false belief users already carry.

With Domatar, the friendly metaphor and the real architecture are the same thing. Rival platforms tend to do one of two things. Some hide a difficult substrate behind a simple surface — Bluesky feels like an ordinary social app only because DIDs, repositories, and cryptographic proofs are concealed; Solid's "your data Pod" pitch sits atop RDF. Others expose a hard substrate directly — Urbit, Holochain, raw blockchains. Domatar exposes a model that is already simple — owned objects, in your account, on a host — so there is no gap between the story a user is told and the system actually running underneath.

The one genuinely surprising claim — that you own your data even when it lives inside someone else's application — is striking to state but invisible to use. In practice a user simply has, say, a bank object in their account that links to their balances and their transactions; that those objects physically sit on the bank's host is irrelevant to everything they do, and the only moment it could matter — moving that host — is something they have no reason to do, because the reason the objects live at the bank is self-evident.

## **PART 12 — THE MODEL THAT FITS THE PROBLEM**

Taken one at a time, each Domatar advantage has some partial rival: personal clouds and Solid for custody, blockchains and Holochain for ownership-without-custody, the Fediverse, Nostr, and AT Protocol for distribution and speech, API standards for interoperability, agent frameworks for AI, and Urbit for a uniform personal substrate. Some of these get one or two pillars genuinely right — and a few, more maturely than Domatar does today. The decisive fact is that none derives ALL of them from a single solution; each gets one or two and bolts on the rest, if it reaches for them at all.

Domatar derives all of them from a single architecture: separate ownership from custody, and reach everything through one uniform, self-describing, location-transparent, ownership-aware object interface. Custody-independent ownership gives you your data, your data inside others' apps, and the clean publisher/platform split. The uniform interface gives you ad-hoc interoperability, inherent distribution, and the agent platform. Because they share a solution, they reinforce one another instead of merely coexisting: your owned objects are interoperable; your interoperable apps are distributed; your distributed apps are inspectable by an agent; your agent is safe because it inherits your ownership and your permissions; and all of it is portable because you own it.

A system that addresses each problem separately is a collection of patches, and a patch can only ever be as good as the problem its author happened to foresee. Domatar does not address the problems separately at all; it never treats them as separate. Underneath, they were always one problem — the user was on the wrong side of the counter — and a model built to put the user on the right side answers all of them at once, including the ones no one thought of before. That is what it means for a model to fit the problem: the solutions follow naturally from the model.

## **PART 13 — CONCLUSION**

The storefront internet asked us to trade ownership for convenience, and the bill for that trade — surveillance, silos, deplatforming, lock-in, and the helplessness of users before the systems they depend on — has come due. Domatar proposes a different bargain, in which convenience and ownership are no longer opposed: rent your hosting from whomever you like, or run your own server, or do some of each, and either way keep ownership of your data, your applications, your speech, and your assets — even when they sit inside someone else's application on someone else's machine.

From that one structural change, the rest follows naturally: applications that interoperate without permission, applications that distribute themselves without protocols, a clean separation of publisher from platform that restores freedom of speech, and a uniform, self-describing network that is the natural habitat for AI agents acting faithfully on our behalf. These are not features to be added; they are consequences of putting the user back on the right side of the counter.

That is why Domatar is not merely a better internet but the natural shape of the next one. The storefront model was an historical accident of how the web grew up, not a law of nature. Ownership is the law of nature. And reaching it asks for no new technology — only a new agreement about how software presents itself, the same kind of agreement the World Wide Web itself once was; Domatar does for applications what the Web did for documents. Sooner or later the internet will be built so that people own their digital lives the way they own everything else that matters to them — and that internet looks like Domatar.
