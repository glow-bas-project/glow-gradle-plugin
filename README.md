# Glow Gradle Plugin

Gradle plugin for Quarkus services: local edit-test loop via **`glowBuild`** (build a Docker image with your latest code and start **only this service** on the shared `glow-devops` compose stack).

Staging and production deployments remain Kubernetes-based; this plugin only targets local/dev workflows.

## Plugin coordinates

- Plugin id: `com.glow.local-env`
- Group: `com.glow`
- Artifact: `glow-gradle-plugin`
- Version source: `VERSION` in `gradle.properties` (currently `1.1.0`)

## Quick start

**One-time** (shared stack):

```bash
export GLOW_HOME="/path/to/code"   # parent of glow-devops and service repos
cp "${GLOW_HOME}/glow-devops/compose/.env.example" "${GLOW_HOME}/glow-devops/compose/.env"
cd "${GLOW_HOME}/glow-devops" && ./scripts/compose-up.sh
```

**Per service repo** — apply the plugin only (no `glowLocalEnv { }` block needed):

```groovy
plugins {
    id 'com.glow.local-env' version '1.1.0'
}
```

**After each code change you want to test:**

```bash
./gradlew glowBuild
```

## Consuming the plugin

Publish locally for development:

```bash
cd glow-gradle-plugin
./gradlew publishToMavenLocal
```

In the service `settings.gradle`, add `mavenLocal()` and the plugin version in `pluginManagement.plugins`.

## Naming (automatic)

From `project.name`, the plugin derives compose and registry names:

| `project.name` | Compose service | Image repo |
|----------------|-----------------|------------|
| `glow-restaurant-service` | `glow-restaurant` | `.../glow-restaurant-service:local` |
| `restaurant` | `glow-restaurant` | `.../glow-restaurant-service:local` |
| `glow-user-service` | `glow-user` | `.../glow-user-service:local` |

Rules: strip trailing `-service`, add `glow-` prefix if missing, registry repo = `${composeKey}-service`.

Defaults:

- `imageSource` = `localBuilt` (build and tag `:local`)
- `composeFile` = `${GLOW_HOME}/glow-devops/compose/docker-compose.yml`
- `composeEnvFile` = `${GLOW_HOME}/glow-devops/compose/.env` (required)

Override only when needed via `glowLocalEnv { }` or `-P` properties.

## Command: `glowBuild`

1. **`quarkusBuild`** (when the `io.quarkus` plugin is applied) — produces `build/quarkus-app/` for `Dockerfile.jvm`
2. `docker build` → `${registryImage}:local` (JVM by default)
3. `docker compose up -d --force-recreate <compose-service>` with `SERVICE_IMAGE_REF` and `COMPOSE_SERVICE_NAME`

Does **not** run full-stack `compose down` / `pull`. Shared infra is started once via `glow-devops/scripts/compose-up.sh`; Compose `depends_on` starts Postgres/Keycloak when needed.

Examples:

```bash
./gradlew glowBuild
./gradlew glowBuild -PglowImageType=native
./gradlew glowBuild -PglowDryRun=true
./gradlew glowBuild -PglowImageSource=registryLatest   # skip local build, use registry :latest
```

Internal tasks `glowBuildImage` and `glowComposeUp` exist but are not the primary workflow.

## Docker compose conventions

Service blocks in `glow-devops/compose/docker-compose.yml` should use:

```yaml
image: ${SERVICE_IMAGE_REF:-${REGISTRY_PREFIX}/glow-restaurant-service:${IMAGE_TAG}}
```

The plugin runs `docker compose --env-file compose/.env` and injects `SERVICE_IMAGE_REF` and `COMPOSE_SERVICE_NAME`.

## Local test with glow-restaurant

```bash
export GLOW_HOME="/path/to/code"
cd glow-gradle-plugin && ./gradlew publishToMavenLocal
cd glow-restaurant
./gradlew glowBuild
curl http://localhost:8081/q/health
```

Restaurant listens on host port **8081** (see `glow-devops` compose).

## Using plugin in other repositories

1. Add `mavenLocal()` (or GitLab Maven) in `settings.gradle` `pluginManagement`.
2. Apply `id 'com.glow.local-env'` in `build.gradle`.
3. Ensure `rootProject.name` matches your compose service (or use `glow-<name>-service`).
4. Add a matching service block in `glow-devops/compose/docker-compose.yml`.

## CI behavior

`.gitlab-ci.yml` includes validation, tests, version guard, and publish on `main`. See repository CI docs for GitLab Maven consumption.

## Development

Requires **JDK 21** for the Gradle daemon (not Java 25). The repo ships `gradle/gradle-daemon-jvm.properties` so `./gradlew` auto-provisions Temurin 21 via the Foojay resolver. If you still see `Unsupported class file major version 69`, run `./gradlew --stop` and retry, or set `JAVA_HOME` to JDK 17/21 before invoking Gradle.

```bash
./gradlew clean check
./gradlew publishToMavenLocal
```
