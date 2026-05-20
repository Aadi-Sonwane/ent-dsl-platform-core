# BuildOS Platform

*Enterprise build orchestration with strict Control Plane / Data Plane separation for multi-tenant Java builds.*

```
┌───────────────────────────────────────────────────────────────────┐
│  CONTROL PLANE (vars/provisionBuildOSPlatform.groovy)              │
│  Job DSL → org folders → multibranch jobs → RBAC authorization    │
│  Invoked once per tenant onboard, never on every commit            │
└──────────────────────────┬────────────────────────────────────────┘
                           │ creates multibranchJob locked to
                           ▼
┌───────────────────────────────────────────────────────────────────┐
│  Jenkinsfile.platform (immutable, platform-owned)                  │
│  @Library('buildos-platform-library')_                             │
│  runBuildOSLifecycle()                                             │
│                                                                   │
│  Developers CANNOT override this file — the multibranch job       │
│  definition pins this path, not project-level Jenkinsfile.        │
└──────────────────────────┬────────────────────────────────────────┘
                           │ per-branch execution
                           ▼
┌───────────────────────────────────────────────────────────────────┐
│  DATA PLANE (vars/runBuildOSLifecycle.groovy)                      │
│  → PipelineExecutionFramework → 50-stage lifecycle                  │
│  Reads project.yml → parse → validate → inherit → orchestrate      │
└───────────────────────────────────────────────────────────────────┘
```

---

## Directory Structure

```
.
├── Jenkinsfile.platform            # Immutable pipeline entry (locked by Job DSL)
├── docker-compose.yml              # Stack definition (Jenkins, Nexus, SonarQube, Gitea)
├── jenkins.yaml                    # JCasC — nodes, credentials, shared library config
├── AGENTS.md                       # Agent context file (architecture summary)
│
├── vars/                           # Jenkins CPS global variables (entry points)
│   ├── provisionBuildOSPlatform.groovy   # Control Plane — Job DSL provisioning
│   └── runBuildOSLifecycle.groovy        # Data Plane — lifecycle runner
│
├── resources/                      # Policy + schema + template resources
│   ├── schemas/
│   │   └── project-schema.yml            # 8-section YAML schema (564 lines)
│   ├── policies/
│   │   ├── branch-protection.yml         # Main/release/develop protection rules
│   │   ├── cleanup-default.yml           # Nexus cleanup policies
│   │   ├── quality-gate-default.yml      # Composite coverage + SonarQube + security gate
│   │   └── security-default.yml          # Trivy, dependency, secret, SBOM, signing baseline
│   ├── maven/
│   │   └── settings-template.xml         # settings.xml with mirrorOf=*, env-var creds
│   └── assembly/
│       └── default-distribution.xml      # Assembly descriptor (tar.gz + zip)
│
└── src/com/enterprise/platform/
    ├── config/                     # (4) YAML parse → schema validate → inherit → orchestrate
    │   ├── EnterpriseYAMLParser.groovy
    │   ├── SchemaValidationManager.groovy
    │   ├── ConfigurationInheritanceManager.groovy
    │   └── ProjectConfigurationManager.groovy
    │
    ├── branching/                  # (3) Branch governance, multibranch config, protection
    │   ├── BranchGovernanceManager.groovy
    │   ├── BranchProtectionManager.groovy
    │   └── MultibranchOrchestrator.groovy
    │
    ├── nexus/                      # (5) Idempotent Nexus provisioning
    │   ├── RepositoryGovernanceManager.groovy
    │   ├── BlobStoreManager.groovy
    │   ├── RepositoryLifecycleManager.groovy
    │   ├── CleanupPolicyManager.groovy
    │   └── NexusProvisioningEngine.groovy
    │
    ├── maven/                      # (5) Settings, distribution, assembly, naming, build
    │   ├── DynamicMavenSettingsGenerator.groovy
    │   ├── DistributionManagementGenerator.groovy
    │   ├── AssemblyPackagingManager.groovy
    │   ├── ArtifactNamingStrategy.groovy
    │   └── BuildLifecycleManager.groovy
    │
    ├── security/                   # (6) Trivy, dependency, secrets, compliance, SBOM, signing
    │   ├── TrivyScanManager.groovy
    │   ├── DependencyScanManager.groovy
    │   ├── SecretScanManager.groovy
    │   ├── ComplianceValidationManager.groovy
    │   ├── SBOMGenerationManager.groovy
    │   └── ArtifactSigningManager.groovy
    │
    ├── quality/                    # (3) Coverage, SonarQube, composite quality gate
    │   ├── CodeCoverageManager.groovy
    │   ├── SonarQubeManager.groovy
    │   └── QualityGateManager.groovy
    │
    ├── rbac/                       # (3) Permission matrix, folder auth, team governance
    │   ├── EnterpriseRBACManager.groovy
    │   ├── FolderAuthorizationManager.groovy
    │   └── TeamGovernanceManager.groovy
    │
    ├── observability/              # (4) Telemetry, audit, metrics, OpenTelemetry
    │   ├── TelemetryManager.groovy
    │   ├── AuditLoggingManager.groovy
    │   ├── BuildMetricsManager.groovy
    │   └── OpenTelemetryManager.groovy
    │
    ├── framework/                  # (5) Pipeline orchestration, recovery, parallelism, retry
    │   ├── PipelineExecutionFramework.groovy     # 1086-line 50-stage orchestrator
    │   ├── ExecutionContextManager.groovy
    │   ├── FailureRecoveryManager.groovy
    │   ├── ParallelExecutionManager.groovy
    │   └── RetryFramework.groovy                # Exponential backoff + jitter retry engine
    │
    └── utils/                      # (5) Logging, shell, validation, execution, retry utility
        ├── LoggingUtils.groovy
        ├── ShellUtils.groovy
        ├── ValidationUtils.groovy
        ├── ExecutionUtils.groovy
        └── RetryUtils.groovy
```

**Total: 45 Groovy source files, 7 resource files, 4 root config files = 55 files.**

---

## Engineering Matrix — 50-Stage Data Plane Lifecycle

The `PipelineExecutionFramework.groovy` orchestrates 50 numbered stages across 9 logical compliance blocks. Each stage is individually wrapped with OpenTelemetry spans, build metrics recording, and failure recovery integration.

### Setup (Stages 01–05)

| Stage | Name | Responsibility |
|-------|------|----------------|
| 01 | Agent Binding | `steps.node("hardened-immutable-jdk17-builder")` — bind to hardened JDK 21 builder |
| 02 | Workspace Hygiene | `rm -rf .buildos-*` — clean stale temp files, create `target/` and `reports/` |
| 03 | Config Validation | Verify `resolvedConfig` is non-empty, check required keys (`project`, `branchGovernance`) |
| 04 | Credentials Injection | `withCredentials` — inject Nexus username/password from Jenkins credential store |
| 05 | Toolchain Verification | Check `java`, `mvn`, `gpg`, `trivy` availability; report missing tools (non-fatal) |

### SCM & Governance (Stages 06–10)

| Stage | Name | Responsibility |
|-------|------|----------------|
| 06 | Branch Governance Check | Regex include/exclude matching, branch normalization, wildcard attack prevention |
| 07 | Multibranch Context | SCM configuration, orphaned item strategy, branch indexing trigger |
| 08 | Changelog Generation | `git log --oneline -20` — capture recent commit history |
| 09 | Protected Branch Enforcement | Compliance report for main/release branches; PR + status check requirements |
| 10 | SCM Metadata Finalize | Audit log of branch, project, business unit metadata |

### Provisioning (Stages 11–15)

| Stage | Name | Responsibility |
|-------|------|----------------|
| 11 | Nexus Blob Store | File blob store with soft quota; idempotent (HTTP 400 → already exists) |
| 12 | Snapshot Repositories | Maven2 hosted repo with SNAPSHOT version policy, ALLOW write |
| 13 | Release Repositories | Maven2 hosted repo with RELEASE version policy, ALLOW_ONCE write |
| 14 | Cleanup Policies | 30d snapshot, 180d release, 7d proxy cache, 1d temp cleanup |
| 15 | RBAC Folder Sync | Delegated to control plane; audit event logged |

### Build (Stages 16–22)

| Stage | Name | Responsibility |
|-------|------|----------------|
| 16 | Maven Settings Generation | In-memory settings.xml — `mirrorOf=*`, env-var credential placeholders |
| 17 | Dependency Resolution | `mvn dependency:resolve` — resolve all transitive dependencies through Nexus |
| 18 | Compilation | `mvn compiler:compile` — fail on compilation error |
| 19 | Unit Tests | `mvn surefire:test` — skip per `maven.skipTests` config |
| 20 | Assembly Packaging | Generate assembly descriptor → `mvn assembly:single` — tar.gz + zip |
| 21 | Artifact Naming | Resolve groupId:artifactId:version, build final artifact name |
| 22 | Build Finalize | `ls -la target/*.jar`, emit audit event |

### Security (Stages 23–28)

| Stage | Name | Responsibility |
|-------|------|----------------|
| 23 | Trivy Scan | Filesystem scan — OS + library vulnerabilities; severity threshold enforcement |
| 24 | Dependency Audit | OWASP Dependency-Check — CVE parsing, CVSS → severity mapping |
| 25 | Secret Detection | 26+ regex patterns + Shannon entropy; fail on CRITICAL, never leak to logs |
| 26 | Compliance Validation | 6 domains: license, crypto, export control, security policy, artifact/data governance |
| 27 | SBOM Generation | CycloneDX or SPDX — Maven plugin or CLI, parse into uniform component list |
| 28 | Artifact Signing | `gpg --detach-sign --armor` — batch signing, verification, key import |

### Quality (Stages 29–33)

| Stage | Name | Responsibility |
|-------|------|----------------|
| 29 | Code Coverage | JaCoCo XML/CSV → Cobertura → Clover; line + branch coverage extraction |
| 30 | SonarQube Analysis | `sonar-scanner` execution; CE task ID capture |
| 31 | Quality Gate Polling | Async polling of SonarQube CE task with configurable timeout (default 300s) |
| 32 | Threshold Enforcement | Composite gate: SonarQube status + coverage thresholds + security results |
| 33 | Quality Report | Structured report with coverage %, gate status, timestamp |

### Publish (Stages 34–40)

| Stage | Name | Responsibility |
|-------|------|----------------|
| 34 | Distribution Management | XML fragment for snapshot + release repository URLs |
| 35 | Nexus Upload | `mvn deploy -DskipTests` — upload artifacts to Nexus |
| 36 | Release Staging | Prepare release metadata, version tagging context |
| 37 | Tag Creation | `git tag -a "${projectName}-${version}"` |
| 38 | Version Bump | Compute next SNAPSHOT version (patch increment) |
| 39 | Publish Verification | Verify target artifacts + `.asc` signatures exist |
| 40 | Publish Finalize | Summary report (nexusUrl, version, artifactName) |

### Observability (Stages 41–45)

| Stage | Name | Responsibility |
|-------|------|----------------|
| 41 | Telemetry Emission | Emit `observability_started` event with correlation ID and elapsed time |
| 42 | Audit Log Finalization | Stage summary audit — completed/total stages |
| 43 | Build Metrics | `metrics.finalizeAndReport()` — total duration, completed stages, build status |
| 44 | OpenTelemetry Span Closure | `otel.injectTraceContext()` — W3C traceparent, OTLP payload |
| 45 | Observability Finalize | Final audit event, correlation ID closure |

### Finalize (Stages 46–50)

| Stage | Name | Responsibility |
|-------|------|----------------|
| 46 | Retry Summary Report | FailureRecoveryManager — total failures, stages affected per retry attempt |
| 47 | Failure Recovery Check | `recovery.hasFailures()` — warn if any unrecovered failures |
| 48 | Workspace Cleanup | `rm -rf .buildos-*` — guarantee no temp files leak |
| 49 | Post-Build Notifications | Audit event with build status (PASSED/FAILED) |
| 50 | Lifecycle Manifest Archive | Full manifest: lifecycleVersion, projectName, durationMs, stageSummary, metrics |

---

## Quick Start — Boot the Stack

### Prerequisites

- Docker Engine 24+
- Docker Compose v2 (plugin)
- ~8 GB free RAM (Jenkins + Nexus + SonarQube + Gitea)

### 1. Configure Nexus Credentials

Before starting, set the correct Nexus admin password in the JCasC file:

```bash
# Read the dynamic password after Nexus first starts
# (Run this after step 2, once Nexus is healthy)
docker exec buildos-nexus cat /nexus-data/admin.password
```

Then update `jenkins.yaml`:

```yaml
# In credentials.systemCredentialsProvider.domainCredentials[0]
password: "<password-from-above>"
```

### 2. Launch the Stack

```bash
# Full clean rebuild — destroys all volumes and starts fresh
docker compose down -v
docker compose up -d --build
```

Wait for all services to become healthy (~3–5 minutes):

```bash
docker compose ps
```

### 3. Verify Service Readiness

```bash
# Check each service
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/login     # Jenkins
curl -s -o /dev/null -w "%{http_code}" http://localhost:8081           # Nexus
curl -s -o /dev/null -w "%{http_code}" http://localhost:9000           # SonarQube
curl -s -o /dev/null -w "%{http_code}" http://localhost:3000           # Gitea
```

All four should return `200` or `302` (redirect to login).

---

## Service Connection Directory

| Service | URL | Default Username | Default Password |
|---------|-----|------------------|------------------|
| Jenkins | http://localhost:8080 | `aadu` | `password123` |
| Nexus | http://localhost:8081 | `admin` | *(dynamic — see below)* |
| SonarQube | http://localhost:9000 | `admin` | `admin` |
| Gitea | http://localhost:3000 | `gitea_admin` | `admin123` |

### Retrieve the Dynamic Nexus Admin Password

Nexus 3 generates a random admin password on first boot. Retrieve it with:

```bash
docker exec buildos-nexus cat /nexus-data/admin.password
```

Copy this value, then either:
- Use it to log into http://localhost:8081 and set a permanent password, or
- Update `jenkins.yaml` credentials and restart Jenkins: `docker compose restart jenkins`

---

## Test Pipeline — Network & Toolchain Validation

Create a new item in Jenkins → Pipeline → paste the following script. This verifies connectivity to the internal service names (`buildos-nexus`, `buildos-sonarqube`) and validates the builder agent toolchain.

```groovy
pipeline {
    agent { label 'hardened-immutable-jdk17-builder' }

    environment {
        NEXUS_URL = 'http://buildos-nexus:8081'
        SONAR_URL = 'http://buildos-sonarqube:9000'
    }

    stages {
        stage('Network — Nexus Connectivity') {
            steps {
                sh """
                    status=\$(curl -s -o /dev/null -w '%{http_code}' \$NEXUS_URL 2>&1)
                    echo "Nexus HTTP status: \$status"
                    if [ "\$status" = "200" ] || [ "\$status" = "302" ] || [ "\$status" = "401" ]; then
                        echo "NEXUS REACHABLE"
                    else
                        echo "NEXUS UNREACHABLE"
                        exit 1
                    fi
                """
            }
        }

        stage('Network — SonarQube Connectivity') {
            steps {
                sh """
                    status=\$(curl -s -o /dev/null -w '%{http_code}' \$SONAR_URL 2>&1)
                    echo "SonarQube HTTP status: \$status"
                    if [ "\$status" = "200" ]; then
                        echo "SONARQUBE REACHABLE"
                    else
                        echo "SONARQUBE UNREACHABLE"
                        # Not fatal — SonarQube takes longer to initialize
                        echo "WARNING: SonarQube may still be starting"
                    fi
                """
            }
        }

        stage('Toolchain — Java') {
            steps {
                sh 'java -version 2>&1'
            }
        }

        stage('Toolchain — Maven') {
            steps {
                sh 'mvn --version 2>&1 | head -3'
            }
        }

        stage('Toolchain — Trivy') {
            steps {
                sh '''
                    trivy --version 2>&1 || echo "TRIVY NOT INSTALLED"
                    command -v trivy || echo "trivy binary not found in PATH"
                '''
            }
        }

        stage('Toolchain — GPG') {
            steps {
                sh 'gpg --version 2>&1 | head -2'
            }
        }

        stage('Toolchain — Workspace') {
            steps {
                sh """
                    echo "Agent hostname: \$(hostname)"
                    echo "Workspace: \$WORKSPACE"
                    echo "Java home: \$JAVA_HOME"
                    echo "Maven home: \$MAVEN_HOME"
                    id
                """
            }
        }
    }
}
```

Expected output: All stages green or yellow (SonarQube connectivity may warn on first boot).

---

## Mandatory Engineering Rules

These constraints are enforced across all 45 Groovy source files. Violations cause `NotSerializableException` at CPS checkpoint resume or runtime failures.

### 1. Every Class Implements Serializable

```groovy
class BlobStoreManager implements Serializable {
    private static final long serialVersionUID = 1L
    // ...
}
```

The Jenkins CPS (Continuation Passing Style) engine checkpoints JVM state between pipeline steps. Any class instance captured in a closure or stored in a pipeline variable must be `Serializable`. Non-serializable fields (`Thread`, `Socket`, unclosed streams) cause `NotSerializableException` at resume time.

**Rule**: All `src/` classes `implements Serializable`. Mark `transient` only for fields that are re-initialized on resume. Every class must define `serialVersionUID`.

### 2. @NonCPS Methods Must Never Call the Jenkins Step API

```groovy
@NonCPS
private long calculateBackoff(int attempt, long baseMs, double multiplier) {
    double exponential = baseMs * Math.pow(multiplier, attempt - 1)
    double jitter = Math.random() * baseMs * 0.1
    long backoff = (long) (exponential + jitter)
    return Math.min(backoff, MAX_BACKOFF_MS)
}
```

The `@NonCPS` annotation tells the CPS transformer to pass the method through without rewriting. This enables normal Groovy semantics (modifiable collections, `Math.pow`, `Math.random`, `StringBuilder`) but **removes the method from the checkpoint/serialization graph**.

**Rule**: `@NonCPS` methods must contain only pure computations. They cannot call `steps.httpRequest()`, `steps.sh()`, `steps.echo()`, `steps.withCredentials()`, `steps.node()`, `steps.fileExists()`, `steps.readFile()`, or any other Jenkins step API. Breaking this rule throws `NonCPSMethodCallException` at runtime.

### 3. Manual JSON Serialization (CPS-Safe)

```groovy
@NonCPS
private String convertToJson(Map data) {
    StringBuilder sb = new StringBuilder()
    sb.append("{")
    // ... manual key-value appends ...
    sb.append("}")
    return sb.toString()
}
```

Groovy's `groovy.json.JsonOutput` and `JsonBuilder` interact poorly with CPS transformation — they can produce `ClassNotFoundException` or serialization errors when the transformed closure tries to deserialize internal JSON library state at checkpoint resume.

**Rule**: All JSON construction in `@NonCPS` boundaries uses manual `StringBuilder.append()`. No `JsonOutput.toJson()`, no `JsonBuilder`, no `groovy.json.*` streaming APIs. The `PipelineExecutionFramework` telemetry events and all Nexus HTTP request bodies build JSON by hand.

### 4. No Raw `sh` — Use ShellUtils

```groovy
// FORBIDDEN — raw sh bypasses timeout and logging
steps.sh(script: "trivy filesystem --severity HIGH /workspace")

// REQUIRED — ShellUtils wrapper
ShellUtils shell = new ShellUtils(steps)
Map result = shell.executeCommand("trivy filesystem --severity HIGH /workspace", 300_000)
```

`ShellUtils` enforces consistent timeout handling, exit code validation, stdout/stderr capture, and structured result maps. Direct `steps.sh()` calls in business logic are prohibited — the only exceptions are in `PipelineExecutionFramework.groovy` itself (which uses `steps.sh` directly for build commands).

### 5. HTTP Calls Must Use RetryFramework

```groovy
// FORBIDDEN — no retry, no backoff, no structured error handling
steps.httpRequest(url: "${nexusUrl}/service/rest/v1/blobstores/file", httpMode: "POST", ...)

// REQUIRED — RetryFramework with exponential backoff
RetryFramework retry = new RetryFramework(steps)
Map response = retry.retry("CreateBlobStore_${name}", {
    steps.httpRequest(url: blobStoreUrl, httpMode: "POST", ...)
}, [maxRetries: 3, backoffBaseMs: 1000, backoffMultiplier: 2.0])
```

`RetryFramework` wraps every HTTP call with configurable exponential backoff, jitter (10% of base), retryable exception classification (timeout, connection refused, 429/502/503), and attempt history tracking. Without it, a transient Nexus restart during provisioning would fail the entire pipeline.

### 6. Maven Settings.xml Is Generated In-Memory

The `settings.xml` file is built in-memory by `DynamicMavenSettingsGenerator` using string concatenation in `@NonCPS` methods. It is written to a temporary file (`.buildos-settings-${correlationId}.xml`) and cleaned up by workspace hygiene stages.

Key invariants:
- `mirrorOf=*` forces all dependency traffic through the corporate Nexus (direct Maven Central access is blocked).
- Credentials use `\${env.NEXUS_USER}` / `\${env.NEXUS_PASS}` placeholder patterns — never literal secrets in generated XML.
- The settings file lives only for the duration of the pipeline run and is guaranteed cleaned by Stage 48 (`executeStage48`).

### 7. Nexus Provisioning Is Idempotent

All Nexus API calls handle HTTP 400 responses gracefully — a 400 for an already-existing blob store, repository, or cleanup policy is treated as success (resource already exists) rather than failure.

```groovy
if (statusCode == 400 && response.content?.contains("already exists")) {
    LoggingUtils.warn("BlobStoreManager", "Blob store '${name}' already exists — treating as success")
    return [name: name, path: path, status: "ALREADY_EXISTS"]
}
```

This means the Control Plane can safely re-run provisioning for a tenant without destroying existing infrastructure.

---

## License

BuildOS Platform — Proprietary. All rights reserved.
