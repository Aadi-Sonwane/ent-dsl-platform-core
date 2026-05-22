# AGENTS.md

## Architecture

Jenkins Shared Library with strict **Control Plane / Data Plane** separation:

```
CONTROL PLANE (vars/provisionBuildOSPlatform.groovy)
  Job DSL → org folders → multibranch jobs → RBAC
  Runs once per tenant (not per commit).
  ↓ creates multibranchJob locked to
Jenkinsfile.platform (immutable, never overridden)
  @Library('buildos-platform-library')_  // underscore required
  runBuildOSLifecycle()
  ↓ per-branch execution
DATA PLANE (vars/runBuildOSLifecycle.groovy)
  → PipelineExecutionFramework → 50-stage lifecycle
  Reads project.yml → parse → validate → inherit → orchestrate
```

## Cardinal rules

- **Every `src/` class `implements Serializable`** with `serialVersionUID = 1L`. CPS checkpoints JVM state — non-serializable fields cause `NotSerializableException` at resume.
- **`@NonCPS` methods must never call Jenkins step API** (`sh`, `httpRequest`, `echo`, `withCredentials`, `node`, `fileExists`, `readFile`, etc.). They bypass CPS transformation — step calls throw at runtime.
- **`@NonCPS` methods must hand-write JSON via `StringBuilder`.** `groovy.json.JsonOutput`, `JsonBuilder`, and `groovy.json.*` APIs corrupt at CPS checkpoint resume.
- **No raw `sh` in business logic.** Use `ShellUtils.execute(command, [timeoutMs: N, captureOutput: true/false])`. Exception: `PipelineExecutionFramework` still uses `steps.sh` directly for build commands (compilation, tests, deploy).
- **Direct HTTP calls must use `RetryFramework.retry(name, closure, [maxRetries:3, backoffBaseMs:1000, backoffMultiplier:2.0])`.** Handles exponential backoff + jitter + 429/502/503 classification.
- **No placeholders, stubs, TODOs, or pseudocode.** Every method, branch, and exception handler is fully implemented.

## Entrypoints

| Layer | File | Purpose |
|---|---|---|
| Control Plane | `vars/provisionBuildOSPlatform.groovy` | Job DSL: folders, multibranch jobs, RBAC — `call(String yamlText)` |
| Data Plane | `vars/runBuildOSLifecycle.groovy` | Reads `project.yml`, instantiates `PipelineExecutionFramework` |
| Pipeline | `Jenkinsfile.platform` | `@Library('buildos-platform-library')_` → `runBuildOSLifecycle()` |
| Schema | `resources/schemas/project-schema.yml` | 8-section, 564-line YAML schema — validated before every build |

## 50-stage lifecycle

`src/.../framework/PipelineExecutionFramework.groovy:129` orchestrates stages 01-50 in 9 groups. Stages 12-15 delegate to `NexusProvisioningEngine`. Security stages 23-28 run in parallel where safe. Each stage is individually wrapped with OpenTelemetry spans and failure recovery.

## Key conventions

- **`PipelineExecutionFramework` uses `steps.sh` directly** for build commands (compilation, tests, Nexus upload). All other classes use `ShellUtils.execute()`.
- **Maven `settings.xml` is generated in-memory** by `DynamicMavenSettingsGenerator` — never written to source. `mirrorOf=*` forces all traffic through Nexus. Credentials use `${env.NEXUS_USER}` / `${env.NEXUS_PASS}` placeholders.
- **Nexus provisioning is idempotent** — HTTP 400 for already-existing resources treated as success, not failure.
- **RBAC uses `authorizationMatrix` with `noInheritance()`** — admins get full rights, developers get read/build/cancel only.
- **Agent label:** `hardened-immutable-jdk17-builder`.
- **`shellUtils.execute()` default valid exit codes:** `[0]` only (pass `validExitCodes: [0,1]` to allow warnings).
- **Multibranch jobs lock to `Jenkinsfile.platform`** via `scriptPath('Jenkinsfile.platform')` — developers cannot override.
- **Project config** must provide `project.yml` at repo root. `demo-java-service/project.yml` is the reference example.

## Local dev stack

```bash
# Full clean boot (destroys volumes)
docker compose down -v && docker compose up -d --build

# Get Nexus dynamic admin password
docker exec buildos-nexus cat /nexus-data/admin.password

# Update password in jenkins.yaml, then:
docker compose restart jenkins
```

Services: Jenkins `:8080` (aadu/password123), Nexus `:8081` (admin/dynamic), SonarQube `:9000` (admin/admin), Gitea `:3000` (gitea_admin/admin123).

## Testing

No test framework or suite exists in this repo. Files under `src/` and `vars/` are Groovy CPS shared-library code testable via `JenkinsRule` or `PlatformScriptEngine` — neither is configured here. The only verification path is the `demo-java-service/` project deployable via the local Docker stack.

## Project structure

```
src/com/enterprise/platform/
├── config/         YAML parse → schema validate → inherit → orchestrate
├── branching/      Branch governance, multibranch, protection
├── nexus/          Idempotent blob store, repo, cleanup provisioning
├── maven/          Settings, distribution, assembly, naming, build
├── security/       Trivy, dependency audit, secrets, compliance, SBOM, signing
├── quality/        Coverage, SonarQube, quality gate
├── rbac/           Permission matrix, folder auth, team governance
├── observability/  Telemetry, audit, metrics, OpenTelemetry
├── framework/      Pipeline orchestration, recovery, parallelism, retry
└── utils/          Logging, shell, validation, execution, retry
vars/               provisionBuildOSPlatform.groovy, runBuildOSLifecycle.groovy
resources/          schemas/, policies/, maven/, assembly/ (templates)
```
