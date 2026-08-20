# Domatar

Domatar is a general, distributed, interoperable application environment.
The platform speaks objects, classes, hosts, and messages — not a single
built-in product. Independent servers on the public internet run the same
artefact, own their own data, and can address one another. Applications
are packaged and installed separately from the platform.

The unit of value is the Application: anyone may publish one, anyone may
run a provider, anyone may sign up. Domatar is the substrate that lets
those three populations cooperate.

Today that substrate is a Tomcat 10.1 / Jakarta EE 10 / Java 17 web
application over MySQL 8, exchanging JSON messages over HTTP. The
reference distribution includes Navigator, Login, Desktop, App Store,
Quippin, Bookstore, Spreadsheet, Money, AI Agent, and the platform's own
app (`domatar`).

- Why this exists: [Why Domatar](docs/Why-Domatar.md)
- How it is specified: [Specification](docs/Specification.md)

This tree is under active development. Copyright Domatar; all rights
reserved. See [LICENSE](LICENSE).

## Layout

```
platform/core     platform runtime (domatar-core)
platform/app      platform app, appId "domatar"
platform/war      platform WAR (domatar.war)
apps/<appId>/     application JARs (navigator, login, desktop, …)
docs/             Why-Domatar.md, Specification.md
docs/spec/        living specifications (Markdown)
deploy/           Docker Compose + Tomcat config
```

Maven is one parent POM (`com.domatar:domatar`) so a change can touch
`platform/core` and `apps/desktop` in a single commit.

## Build

Prerequisites: Java 17, Maven 3.8+, Docker.

Always `clean package` (never bare `package`). Incremental builds can
leave Eclipse ECJ stubs in app JARs under `WEB-INF/apps/`, and then
`/Setup` reports the app is not loaded.

```
mvn -q -pl platform/war clean package -am -DskipTests
```

The WAR is `platform/war/target/domatar.war`.

## Run (single node)

From the repository root, after a successful build:

```
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml exec tomcat \
  curl -s http://localhost:8080/domatar/Setup
```

Then open http://localhost:8080/domatar/

If host port 8080 is already in use:

```
TOMCAT_HOST_PORT=9080 docker compose -f deploy/docker-compose.yml up -d
```

Compose passwords (`MYSQL_ROOT_PASSWORD`, `DOMATAR_ADMIN_PASSWORD`) are
**development defaults**. They must match the JDBC URL in
`platform/war/src/main/webapp/WEB-INF/web.xml`. Do not use them on a
machine reachable from the internet. Provider operational keys are
generated on first run into a Docker volume; this repository does not
commit private keysets.

The provider administrator account is `<PrvId>@<PrvId>` (for this
compose file, `prv1@prv1`) with the configured admin password.

## Contributing and security

- [CONTRIBUTING.md](CONTRIBUTING.md) — style, specs, how to propose a change
- [SECURITY.md](SECURITY.md) — how to report a vulnerability
