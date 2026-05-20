# AGENTS.md

## What this is

Jenkins Shared Library for the **BuildOS Platform** — an enterprise build orchestration
system with strict **Control Plane** / **Data Plane** separation for multi-tenant Java builds.

```
┌─────────────────────────────────────────────────────────┐
│  CONTROL PLANE (provisionBuildOSPlatform.groovy)        │
│  Job DSL → org folders → multibranch jobs → RBAC        │
│  Runs once per tenant onboarding.                       │
└──────────────┬──────────────────────────────────────────┘
               │ creates multibranchJob pointing at
               ▼
┌─────────────────────────────────────────────────────────┐
│  Jenkinsfile.platform (immutable, platform-owned)       │
│  Explicit @Library import, calls runBuildOSLifecycle()  │
└──────────────┬──────────────────────────────────────────┘
               │ per-branch execution
               ▼
┌─────────────────────────────────────────────────────────┐
│  DATA PLANE (runBuildOSLifecycle.groovy)                │
│  → PipelineExecutionFramework → 50-stage lifecycle      │
└─────────────────────────────────────────────────────────┘
```

## Cardinal rules

- **Every `src/` class must `implements Serializable`.** CPS checkpoints the JVM;
  non-serializable state causes `NotSerializableException` at resume.
- **`@NonCPS` methods must never call Jenkins step API** (`sh`, `httpRequest`, `echo`, etc.).
  They run outside CPS transformation and steps will throw.
- **No placeholders, stubs, TODOs, or pseudocode.** Every method, branch, and exception
  handler is fully implemented.
- **Raw `sh` is forbidden.** Use `ShellUtils` wrappers for all shell execution.
- **Direct HTTP calls must use `RetryFramework`** with exponential backoff and structured
  error handling.

## Entrypoints

| Layer | File | Purpose |
|---|---|---|
| Control Plane | `vars/provisionBuildOSPlatform.groovy` | Job DSL onboarding: folders, RBAC, multibranch jobs |
| Data Plane | `vars/runBuildOSLifecycle.groovy` | Reads `project.yml`, delegates to `PipelineExecutionFramework` |
| Pipeline | `Jenkinsfile.platform` | Fixed library import, calls Data Plane — never overridden |

## 50-stage lifecycle (logical groupings)

`PipelineExecutionFramework.groovy` executes these stage groups in order:

1. **Setup** (01-05): Agent binding, workspace hygiene, config validation, credentials
   injection, toolchain verification
2. **SCM & Governance** (06-10): Branch governance check, multibranch context, changelog,
   protected-branch enforcement
3. **Provisioning** (11-15): Nexus blob store, snapshot/release/group repos, cleanup policies,
   RBAC folder sync
4. **Build** (16-22): Maven settings generation, dependency resolution, compilation, unit tests,
   assembly packaging, artifact naming
5. **Security** (23-28): Trivy scan, dependency audit, secret detection, compliance validation,
   SBOM generation, artifact signing *(parallel where safe)*
6. **Quality** (29-33): Code coverage, SonarQube analysis, quality gate polling, threshold
   enforcement
7. **Publish** (34-40): Distribution management, Nexus upload, release staging, tag creation,
   version bump
8. **Observability** (41-45): Telemetry emission, audit log finalization, build metrics,
   OpenTelemetry span closure
9. **Finalize** (46-50): Retry-summary report, failure recovery check, workspace cleanup,
   post-build notifications, lifecycle manifest archive

## Project structure

```
src/com/enterprise/platform/
├── config/       YAML parsing, schema validation, inheritance
├── nexus/        Blob store, repo lifecycle, cleanup, governance
├── maven/        Settings gen, distribution mgmt, assembly, naming
├── security/     Trivy, dependency scan, secrets, compliance, SBOM, signing
├── quality/      Coverage, SonarQube, quality gate
├── rbac/         Enterprise RBAC, folder auth, team governance
├── branching/    Branch governance, multibranch orchestration, protection
├── framework/    Pipeline execution, retry, recovery, parallel, context
├── observability/ Telemetry, audit, metrics, OpenTelemetry
└── utils/        Logging, shell, validation, execution, retry
```

## Key conventions

- **Maven `settings.xml` is generated in-memory** — never written to source. `mirrorOf=*`
  forces all traffic through corporate Nexus (direct Maven Central blocked).
- **Nexus provisioning is idempotent** — HTTP 400 for existing resources is handled
  gracefully, not failed.
- **All YAML input is validated against `resources/schemas/project-schema.yml`** before
  any provisioning or build action.
- **Multibranch jobs lock to `Jenkinsfile.platform`** — developers cannot override the
  pipeline path.
- **RBAC uses `authorizationMatrix` with inheritance disabled** — admins get full rights,
  developers get read/build/cancel only.
- **Agent label: `hardened-immutable-jdk17-builder`**.
- **No pom.xml or Gradle build** — this is a Groovy-only shared library; testing uses
  Jenkins `PlatformScriptEngine` or `JenkinsRule`.
