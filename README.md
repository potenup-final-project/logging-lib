# gop-logging-lib

Common logging library for `pg_core`, `worker`, and `backoffice`.

## Modules

- `gop-logging-contract`: shared contracts (`@LogPrefix`, `@LogSuffix`, enums, logger interface)
- `gop-logging-core`: step context/resolution, masking, structured logger implementation
- `gop-logging-spring`: Spring AOP + auto-configuration integration

## Build

- `./gradlew build`
- `./gradlew check`
- `./gradlew clean`

## Quality Gates

- `./gradlew validateLoggingConventions`
  - blocks `@LogPrefix("...")` string literals (must use `StepPrefix.*`)
  - blocks direct `step =` usage in `structuredLogger.*(...)`
  - validates `StepPrefix` values against `^[a-z]+(\.[a-z0-9]+)+$`

## Runtime Config

- `LOG_SERVICE_NAME` (required): `pg_core` | `worker` | `backoffice`
- `LOG_RATE_LIMIT_PER_SECOND` (optional, default: `100`)
- `LOG_TRACE_EXCLUDED_PATH_PREFIXES` (optional, comma-separated)
  - default: `/actuator,/swagger-ui,/v3/api-docs`

## Coroutine Propagation

- Use `MdcStepCoroutineContext.captureCurrent()` to propagate MDC + StepContext in coroutine boundaries.

## Publish (GitHub Packages)

- Configure Gradle properties (CI recommended):
  - `loggingLibVersion` (default `1.0.0-SNAPSHOT`)
  - `githubPackagesSnapshotUrl` or `githubPackagesReleaseUrl`
  - `githubPackagesUser` (default: `GITHUB_ACTOR`)
  - `githubPackagesToken` (default: `GITHUB_TOKEN`)
- Publish command:
  - `./gradlew publish`

Repository routing is selected by version suffix:
- `*-SNAPSHOT` -> `githubPackagesSnapshotUrl` (fallback to release URL)
- otherwise -> `githubPackagesReleaseUrl` (fallback to snapshot URL)

## GitHub Actions Publish

Workflow: `.github/workflows/publish-github-packages.yml`

Required permissions:
- `packages: write`
- `contents: read`

Repository secret required:
- none (uses built-in `GITHUB_TOKEN`)

Trigger rules:
- push to `main`: publish snapshot version
- tag `vX.Y.Z`: publish release version `X.Y.Z`
- manual dispatch: optional `release_version` override
