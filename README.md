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

## Publish (CodeArtifact)

- Configure Gradle properties (CI recommended):
  - `loggingLibVersion` (default `1.0.0-SNAPSHOT`)
  - `codeArtifactSnapshotUrl` or `codeArtifactReleaseUrl`
  - `codeArtifactUser` (default `aws`)
  - `codeArtifactToken` (ephemeral token)
- Publish command:
  - `./gradlew publish`

Repository routing is selected by version suffix:
- `*-SNAPSHOT` -> `codeArtifactSnapshotUrl`
- otherwise -> `codeArtifactReleaseUrl`

## GitHub Actions Publish

Workflow: `.github/workflows/publish-codeartifact.yml`

Repository variables required:
- `AWS_REGION`
- `CODEARTIFACT_DOMAIN`
- `CODEARTIFACT_DOMAIN_OWNER`
- `CODEARTIFACT_RELEASE_REPOSITORY`
- `CODEARTIFACT_SNAPSHOT_REPOSITORY` (optional, defaults to release repository)

Repository secret required:
- `AWS_CODEARTIFACT_PUBLISH_ROLE_ARN` (OIDC assume role)

Trigger rules:
- push to `main`: publish snapshot version
- tag `vX.Y.Z`: publish release version `X.Y.Z`
- manual dispatch: optional `release_version` override
