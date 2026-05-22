# AGENTS.md — BuildOS Platform Long-Term Memory

> **Purpose:** If context resets, this file restores full understanding of the
> production simulation layout — architecture, files, networking, credentials,
> hooks, and pipeline behavior. Read this first on session start.

---

## 1. WORKSPACE CORE FILES & SCHEMAS

| File | Path | Purpose |
|---|---|---|
| Project config | `project.yml` (at repo root) | Custom per-application configuration consumed by the pipeline. |
| Schema dictionary | `resources/schemas/project-schema.yml` | 564-line, 8-section YAML schema. Every `project.yml` is validated against this before any build action. |
| Parameters reference | `PARAMETERS.MD` (workspace root) | Exhaustive 286-line reference dissecting every `project.yml` parameter with 6-column tables (path, type, required, default, constraints, plain-English description). |
| Setup guide | `SETUP.MD` (workspace root) | 440-line beginner-friendly manual covering the full 4-node ecosystem setup with port maps, credential walkthroughs, webhook configs, and troubleshooting. |
| Agent instructions | `AGENTS.md` (THIS FILE) | Long-term memory for agent context recovery. |

### Shared library source tree

```
/workspace/
├── AGENTS.md                          ← you are here
├── PARAMETERS.MD                      ← project.yml parameter dictionary
├── SETUP.MD                           ← non-technical setup guide
├── Jenkinsfile.platform               ← immutable pipeline entrypoint
├── docker-compose.yml                 ← local dev stack definition
├── jenkins.yaml                       ← Jenkins-as-Code configuration
├── demo-java-service/                 ← reference smoke-test project
│   ├── project.yml                    ← example project config
│   └── pom.xml / src/                 ← Spring Boot app source
├── resources/
│   ├── schemas/project-schema.yml     ← canonical schema
│   ├── policies/                      ← branch-protection, cleanup, quality-gate, security defaults
│   ├── maven/settings-template.xml    ← Maven settings template
│   └── assembly/default-distribution.xml
├── vars/
│   ├── provisionBuildOSPlatform.groovy  ← Control Plane entrypoint
│   └── runBuildOSLifecycle.groovy       ← Data Plane entrypoint
└── src/com/enterprise/platform/
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
```

### Reference `project.yml` (`demo-java-service/project.yml`)

```yaml
version: "1.0"
project:
  metadata:
    projectName: "demo-java-service"
    businessUnit: "PlatformEngineering"
    team: "core-devops"
    repositoryUrl: "http://buildos-git:3000/aadu/demo-java-service.git"
    scmCredentialsId: "git"
    description: "Spring Boot Microservice Platform Smoke Test Project Target"
  version: "1.0.0-SNAPSHOT"

nexus:
  url: "http://buildos-nexus:8081"
  credentialsId: "enterprise-nexus-credentials"

branchGovernance:
  includePatterns:
    - "^main$"
    - "^feature/.*"

rbac:
  folderAuth:
    useFolderBasedAuth: true
    admins: ["aadu"]

maven:
  pomPath: "pom.xml"
  skipTests: false

security:
  dependencyScan:
    enabled: true
    failOnCritical: true
  secretScan:
    enabled: true

quality:
  coverage:
    tool: "jacoco"
    lineCoverageThreshold: 80
  sonarQube:
    hostUrl: "http://buildos-sonarqube:9000"

observability:
  audit:
    enabled: true
    logLevel: "INFO"
```

---

## 2. INFRASTRUCTURE PORTS & NETWORKING (DOCKER BRIDGE)

All four services run in Docker containers on the same bridge network. Containers
communicate using **service names**, not localhost. `localhost` inside a container
refers to that container itself — use the internal DNS names below when configuring
cross-service URLs.

| Service | Host Port | Internal Port | Container Name | Browser URL | Container-to-Container URL |
|---|---|---|---|---|---|
| **Gitea** (Git SCM) | `:3000` | `:3000` | `buildos-git` | http://localhost:3000 | http://buildos-git:3000 |
| **Jenkins** (CI/CD) | `:8080` | `:8080` | `buildos-jenkins` | http://localhost:8080 | http://buildos-jenkins:8080 |
| **SonarQube** (Quality) | `:9000` | `:9000` | `buildos-sonarqube` | http://localhost:9000 | http://buildos-sonarqube:9000 |
| **Nexus** (Artifacts) | `:8081` | `:8081` | `buildos-nexus` | http://localhost:8081 | http://buildos-nexus:8081 |

### SonarQube case sensitivity

All `withSonarQubeEnv()` calls in pipeline code **must** use the case-sensitive
environment name `'SonarQube'` (capital S, capital Q). The Jenkins SonarQube plugin
configuration registers the server under this exact name. Using a different casing
(`sonarqube`, `sonarQube`) will fail at runtime with a "sonar server not configured"
error. A scan of `src/` and `vars/` has confirmed zero references exist — this rule
applies when adding new SonarQube stages.

---

## 3. UNIFIED CREDENTIALS MAP

These three Jenkins credential IDs are hard-referenced throughout the codebase and
**must not drift**. Every `project.yml` and every provisioning script uses these
exact IDs. A global alignment pass changed the old default
`enterprise-platform-vcs-ssh-key` to `git` in 5 files.

| Jenkins Credential ID | Kind | Value | Used For |
|---|---|---|---|
| **`git`** | Username with password | `aadu` / `Pass@1234567` | Gitea SCM authentication — Jenkins clones repositories with this identity. |
| **`sonar-auth-token`** | Secret text | `sqp_...` token generated from SonarQube UI | SonarQube analysis authentication. Generate via: SonarQube → My Account → Security → Generate Tokens → name `buildos-jenkins-token`. |
| **`enterprise-nexus-credentials`** | Username with password | `admin` / `Pass@1234567` | Nexus artifact uploads (snapshot + release deployment). |

**Drift prevention:** The `scmCredentialsId` default in `project-schema.yml` is `"git"`.
All Groovy code that falls back to a default uses `"git"`. If adding a new credential
reference, always reuse one of these three IDs — never invent a new one.

---

## 4. COMPILATION & HOOK INTEGRATION SETTINGS

### Gitea → Jenkins webhook

When a developer pushes code to Gitea, a webhook notifies Jenkins to trigger a scan.
Configure this in the Gitea repository: **Settings → Webhooks → Add Webhook → Gitea**.

| Field | Value |
|---|---|
| Target URL | `http://buildos-jenkins:8080/git/notifyCommit?url=http://buildos-git:3000/aadu/demo-java-service.git` |
| HTTP Method | `POST` |
| Content Type | `application/json` |
| Trigger | Push events |
| Active | Checked |

Jenkins's `notifyCommit` endpoint is picky:
- The `url=` parameter must **not** have a trailing slash
- The `url=` parameter must **not** include `.git` at the end
- Example of a **broken** URL: `http://buildos-jenkins:8080/git/notifyCommit?url=http://buildos-git:3000/aadu/demo-java-service.git/`
- If wrong, Jenkins logs `No git consumers` and the build never triggers.

### ValidationUtils.groovy — Critical regex rule

File: `src/com/enterprise/platform/utils/ValidationUtils.groovy`, line 8.

The `emailRegex` **must always use Groovy slashy-string syntax** (`/pattern/`), not
double-quoted string syntax (`"pattern"`). Double-quoted strings trigger `$`
variable interpolation — if the regex contains literal dollar signs (e.g., for
end-of-string `$` anchors), Groovy treats them as GString expressions and the
compilation fails with a missing property error.

```groovy
// CORRECT — Groovy slashy syntax (no interpolation):
def emailRegex = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/

// WRONG — double-quoted string causes $ interpolation failures:
// private static final String EMAIL_PATTERN = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
```

This was fixed in commit `b482597`. Any future edits to this file must preserve the
slashy syntax for regex patterns that contain `$`.

### Maven settings.xml

Generated in-memory by `DynamicMavenSettingsGenerator` — never written to source
control. Key properties:
- `mirrorOf=*` forces all dependency traffic through Nexus (direct Maven Central blocked)
- Credentials use `${env.NEXUS_USER}` / `${env.NEXUS_PASS}` placeholders resolved at build time

---

## 5. SYSTEM OPERATIONS & PIPELINE BEHAVIOR

### Architecture overview

```
  ┌──────────────┐       webhook       ┌──────────────┐
  │    Gitea     │─────┬──────────────►│   Jenkins    │
  │  (Git SCM)   │     │               │  (CI/CD)     │
  │   :3000      │◄────┘               │   :8080      │
  └──────────────┘   git push           └──────┬───────┘
                                               │
                     ┌─────────────────────────┼─────────────┐
                     │                         │             │
                     ▼                         ▼             ▼
              ┌──────────────┐          ┌──────────────┐
              │  SonarQube   │          │    Nexus     │
              │  Quality     │          │  Artifacts   │
              │   :9000      │          │   :8081      │
              └──────────────┘          └──────────────┘
```

### Control Plane / Data Plane separation

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

### Pipeline trigger sequence

1. Developer runs `git push` to Gitea (`:3000`).
2. Gitea sends a `POST` webhook to `http://buildos-jenkins:8080/git/notifyCommit?url=http://buildos-git:3000/aadu/demo-java-service.git`.
3. Jenkins Multibranch Pipeline scans the repository and detects the pushed branch.
4. If the branch matches an `includePattern` in `branchGovernance`, the pipeline executes.
5. `runBuildOSLifecycle()` reads `project.yml` from the repository root.
6. `PipelineExecutionFramework` orchestrates the 50-stage lifecycle (9 groups):

| Group | Stages | Delegates To |
|---|---|---|
| Setup | 01–05 | Agent binding, workspace hygiene, config validation, credentials injection, toolchain verification |
| SCM & Governance | 06–10 | Branch governance check, multibranch context, changelog, protected-branch enforcement |
| Provisioning | 11–15 | **NexusProvisioningEngine** — blob store, snapshot/release/group repos, cleanup policies, RBAC folder sync |
| Build | 16–22 | DynamicMavenSettingsGenerator, dependency resolution, compilation, unit tests, assembly packaging, artifact naming |
| Security | 23–28 | Trivy scan, dependency audit, secret detection, compliance, SBOM, signing ***(parallel where safe)*** |
| Quality | 29–33 | Code coverage, **SonarQube** analysis, quality gate polling, threshold enforcement |
| Publish | 34–40 | Distribution management, Nexus upload, release staging, tag creation, version bump |
| Observability | 41–45 | Telemetry emission, audit log finalization, build metrics, OpenTelemetry span closure |
| Finalize | 46–50 | Retry-summary report, failure recovery check, workspace cleanup, post-build notifications, lifecycle manifest archive |

7. Each stage is individually wrapped with OpenTelemetry spans and failure recovery.
8. On success: the compiled `.jar` is published to Nexus (snapshot or release repository
   per `distributionManagement` in Maven config).
9. On failure: `FailureRecoveryManager` handles retry or abort, and the audit trail
   records the outcome.

### Cardinal rules for code changes

- **Every `src/` class `implements Serializable`** with `serialVersionUID = 1L`. CPS checkpoints JVM state — non-serializable fields cause `NotSerializableException` at resume.
- **`@NonCPS` methods must never call Jenkins step API** (`sh`, `httpRequest`, `echo`, `withCredentials`, `node`, `fileExists`, `readFile`, etc.). They bypass CPS transformation — step calls throw at runtime.
- **`@NonCPS` methods must hand-write JSON via `StringBuilder`.** `groovy.json.JsonOutput`, `JsonBuilder`, and `groovy.json.*` APIs corrupt at CPS checkpoint resume.
- **No raw `sh` in business logic.** Use `ShellUtils.execute(command, [timeoutMs: N, captureOutput: true/false])`. Exception: `PipelineExecutionFramework` uses `steps.sh` directly for build commands.
- **Direct HTTP calls must use `RetryFramework.retry(name, closure, [maxRetries:3, backoffBaseMs:1000, backoffMultiplier:2.0])`.** Handles exponential backoff + jitter + 429/502/503 classification.
- **No placeholders, stubs, TODOs, or pseudocode.** Every method, branch, and exception handler is fully implemented.
- **`shellUtils.execute()` default valid exit codes:** `[0]` only (pass `validExitCodes: [0,1]` to allow warnings).
- **Agent label:** `hardened-immutable-jdk17-builder`.
- **Multibranch jobs** lock to `Jenkinsfile.platform` via `scriptPath('Jenkinsfile.platform')` — developers never override.
- **Nexus provisioning is idempotent** — HTTP 400 for already-existing resources treated as success, not failure.
- **RBAC uses `authorizationMatrix` with `noInheritance()`** — admins get full rights, developers get read/build/cancel only.

---

## 6. GIT HISTORY & PUSH STATUS

| Ref | Hash | Message | Notes |
|---|---|---|---|
| HEAD | `b482597` | `fix: total framework integration alignment, regex patch, and credentials cleanup` | Committed locally. Changes 6 files: slashy regex fix, unified `git` credential ID across all code, submodule ref update. |
| Root | `43c2725` | `feat: initialize enterprise dsl platform automation blueprint` | Initial 17k-line commit with all 57 source files, schema, docker-compose, Jenkins config, and documentation. |

**Remote:** `git@github.com:Aadi-Sonwane/ent-dsl-platform-core.git` (SSH)  
**Status:** 1 commit ahead of `origin/main`. Push requires a GitHub PAT or SSH key.
**Push command when ready:** `git push -f origin main`

---

## 7. LOCAL DEV STACK

```bash
# Full clean boot (destroys all volumes — resets everything)
docker compose down -v && docker compose up -d --build

# Get Nexus admin password (rotated on every fresh boot)
docker exec buildos-nexus cat /nexus-data/admin.password

# Get Jenkins unlock password
docker exec buildos-jenkins cat /var/jenkins_home/secrets/initialAdminPassword

# Restart Jenkins after updating nexus password in jenkins.yaml
docker compose restart jenkins
```

| Service | Credentials (initial) |
|---|---|
| Gitea `:3000` | `gitea_admin` / `admin123` |
| Jenkins `:8080` | `aadu` / `password123` |
| SonarQube `:9000` | `admin` / `admin` |
| Nexus `:8081` | `admin` / (dynamic, see command above) |

### Testing

No formal test framework. The only verification path is deploying the
`demo-java-service/` project through the Docker stack:
1. Push the `demo-java-service/` code to Gitea
2. Confirm the Jenkins multibranch pipeline triggers
3. Verify the build runs all 50 stages to completion
4. Check Nexus for the published artifact

---

*End of AGENTS.md — BuildOS Platform Long-Term Memory v2.0*
