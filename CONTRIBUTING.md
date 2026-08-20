# Contributing to Domatar

## Specs travel with the code

Living specifications under `docs/spec/` are the public HOW.
When you change behaviour, update the relevant spec in the same change
(status marker and cross-refs). Do not leave the spec describing a
system the code no longer implements.

- Why: [`docs/Why-Domatar.md`](docs/Why-Domatar.md)
- Spec reading order: [`docs/Specification.md`](docs/Specification.md)
- Java style: [`docs/spec/platform/CodeStyle.md`](docs/spec/platform/CodeStyle.md)

## Java

- **Allman braces** — opening brace on the next line.
- **2-space indent**, never tabs. CRLF line endings. File ends with one newline.
- `final` locals where practical. Comments explain *why*, not the change history.
- Base64: `com.domatar.util.Base64Encoder`, never `java.util.Base64`.
- Hashing: `com.domatar.crypto.KeyOps.sha256`.
- No new third-party dependencies.

Do not change v1 actId / ownId fingerprint math, `Base64Encoder`, or
`DomId.HOST_SEP`. Route fingerprint derivation through `AccountKeys`.

## Build

Always `mvn … clean package` (never bare `package`). Incremental Maven
builds can leave unresolved-compilation stubs in app JARs.

```
mvn -q -pl <modules>,platform/war clean package -am -DskipTests
```

Redeploy the WAR with Docker Compose (`deploy/docker-compose.yml`) and
hit `/domatar/Setup` on the node after `--force-recreate`.

## Pull requests

One PR may change `platform/core` and an app together — that is
intentional. Keep the change reviewable: say what behaviour changed and
which spec PART you updated.
