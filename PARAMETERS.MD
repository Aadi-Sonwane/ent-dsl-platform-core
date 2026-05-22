# BuildOS Platform — `project.yml` Parameters Reference Dictionary

> **Dual-audience:** Technical readers use the constraints column for validation rules;
> non-technical readers read the description column to understand what each field does.

All values go inside a file named `project.yml` at the root of your repository. The
framework validates every field against the schema at
`resources/schemas/project-schema.yml` before any build step runs.

### Scope Legend

| Scope | Meaning |
|---|---|
| **Global Only** | Set in `resources/platform/global.yml` — the Single Source of Truth. Do NOT put in `project.yml`. |
| **Local Only** | Set per-application in `project.yml`. Required identity metadata that varies per project. |
| **Cascading Overridable** | Has a default in `global.yml` (or base framework). A `project.yml` value overrides it for that specific application. |

---

## 1. `project` Block — Project Identity

### `project.metadata`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `project.metadata.projectName` | string | **Yes** | — | Pattern: `^[a-z0-9]([a-z0-9-]*[a-z0-9])?$` | A short, lowercase name for your project. Used to create folder names in Jenkins, repository IDs in Nexus, and artifact prefixes. Hyphens allowed. Examples: `my-java-app`, `payment-service`. | **Local Only** |
| `project.metadata.businessUnit` | string | **Yes** | — | Pattern: `^[A-Z][a-zA-Z0-9-]{2,32}$` | Your division or department name within the company. Starts with a capital letter. Examples: `PlatformEngineering`, `Payments`, `DataPlatform`. | **Local Only** |
| `project.metadata.team` | string | **Yes** | — | Pattern: `^[a-z0-9]([a-z0-9-]*[a-z0-9])?$` | The team that owns this project. Lowercase, hyphen-separated. Examples: `core-devops`, `backend-api`. | **Local Only** |
| `project.metadata.repositoryUrl` | string | **Yes** | — | Pattern: `^https?://.*` | The full URL to your source code repository (starting with http:// or https://). Jenkins clones code from here. Example: `http://buildos-git:3000/gitea_admin/demo-java-service.git`. | **Local Only** |
| `project.metadata.scmCredentialsId` | string | No | `git` | — | The Jenkins credential ID used to clone the repository. Our platform standard is `git`. Change only if you use a different credentials store. | **Local Only** |
| `project.metadata.description` | string | No | — | Max length: 1024 chars | A sentence or two explaining what your project does. Displayed in Jenkins folder descriptions and dashboards. | **Local Only** |
| `project.metadata.tags` | list of string | No | — | — | Labels to help search and categorize your project in Jenkins views. Examples: `spring-boot`, `microservice`, `critical`. | **Local Only** |

### `project.version`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `project.version` | string | No | `1.0.0-SNAPSHOT` | Pattern: `^\d+\.\d+\.\d+(-SNAPSHOT)?$` | The version number of your project. Follows semantic versioning (major.minor.patch). Add `-SNAPSHOT` for in-development builds. Example: `2.1.0-SNAPSHOT`. | **Local Only** |

### `project.parent`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `project.parent.ref` | string | **Yes** (if parent block present) | — | Pattern: `^[a-z0-9]([a-z0-9-]*[a-z0-9])?$` | The project name of a parent project whose settings you want to reuse. The parent's configuration is loaded first, then your overrides are merged on top. | **Local Only** |
| `project.parent.inherit` | list of string | No | — | Allowed: `nexus`, `security`, `quality`, `maven`, `branchGovernance`, `rbac`, `policies`, `observability` | Which sections of the parent project's configuration to inherit. Pick only the sections you want to copy. If empty, nothing is inherited from the parent even though the parent is referenced. | **Local Only** |

---

## 2. `nexus` Block — Artifact Repository

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `nexus.url` | string | **Yes** (if block present) | — | Pattern: `^https?://[a-zA-Z0-9._:-]+(:\d+)?(/.*)?$` | The web address of your Nexus repository manager. Jenkins uploads build artifacts here. Example: `http://buildos-nexus:8081`. | **Global Only** |
| `nexus.credentialsId` | string | No | `enterprise-nexus-credentials` | — | The Jenkins credential ID holding the Nexus username and password. The platform default is `enterprise-nexus-credentials`. | **Global Only** |

### `nexus.blobStore`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `nexus.blobStore.name` | string | No | — | Pattern: `^[a-zA-Z0-9][a-zA-Z0-9._-]*$`, max 64 chars | A friendly name for the storage location where Nexus saves your artifacts. Like naming a filing cabinet. | **Cascading Overridable** |
| `nexus.blobStore.path` | string | No | — | — | The file-system directory inside the Nexus container where blob data is stored. Leave blank to use Nexus's default path. | **Cascading Overridable** |
| `nexus.blobStore.softQuota.type` | string | No | — | Allowed: `space`, `maxComponents` | The kind of storage limit to enforce. `space` limits by disk usage; `maxComponents` limits by number of stored items. | **Cascading Overridable** |
| `nexus.blobStore.softQuota.limit` | number | No | — | Minimum: 1 | The numerical limit for the quota type (e.g., 100 GB for space, 50000 for max components). | **Cascading Overridable** |

### `nexus.repositories`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `nexus.repositories.snapshot.format` | string | No | `maven2` | — | The package format for snapshot (in-development) builds. `maven2` is the standard for Java/Maven projects. | **Cascading Overridable** |
| `nexus.repositories.snapshot.versionPolicy` | string | No | `SNAPSHOT` | — | Whether this repository accepts snapshot (unstable) versions, release (stable) versions, or both. | **Cascading Overridable** |
| `nexus.repositories.snapshot.writePolicy` | string | No | `ALLOW` | — | Whether Jenkins is allowed to upload new files to this repository. `ALLOW` permits writes; `ALLOW_ONCE` means write once and never overwrite; `DENY` blocks uploads. | **Cascading Overridable** |
| `nexus.repositories.snapshot.cleanup.policyNames` | list of string | No | — | — | Names of cleanup policies to apply to this repository. Old snapshots will be deleted according to those rules. | **Cascading Overridable** |
| `nexus.repositories.release.format` | string | No | `maven2` | — | The package format for stable release builds. | **Cascading Overridable** |
| `nexus.repositories.release.versionPolicy` | string | No | `RELEASE` | — | Marks this repository as the destination for stable release versions. | **Cascading Overridable** |
| `nexus.repositories.release.writePolicy` | string | No | `ALLOW_ONCE` | — | `ALLOW_ONCE` means a released artifact can never be overwritten — this prevents accidental replacement of published versions. | **Cascading Overridable** |
| `nexus.repositories.release.cleanup.policyNames` | list of string | No | — | — | Names of cleanup policies to apply to the release repository. | **Cascading Overridable** |
| `nexus.repositories.group.format` | string | No | `maven2` | — | Format for the group repository that combines snapshot+release into one URL. | **Cascading Overridable** |
| `nexus.repositories.group.memberRepositories` | list of string | No | — | — | The names of the snapshot and release repositories that this group pulls from. Developers use the group URL to get both snapshot and release artifacts transparently. | **Cascading Overridable** |

### `nexus.cleanupPolicies[]`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `nexus.cleanupPolicies[].name` | string | **Yes** | — | — | A name for this cleanup rule. Example: `delete-old-snapshots`. | **Cascading Overridable** |
| `nexus.cleanupPolicies[].format` | string | No | `ALL_FORMATS` | — | Which artifact format this cleanup rule applies to. `ALL_FORMATS` cleans everything; specify `maven2` to target only Maven artifacts. | **Cascading Overridable** |
| `nexus.cleanupPolicies[].criteria.lastDownloaded` | number | No | — | Minimum: 1 days | Delete artifacts that have not been downloaded in this many days. Set to 90 to remove anything untouched for 3 months. | **Cascading Overridable** |
| `nexus.cleanupPolicies[].criteria.lastBlobUpdated` | number | No | — | Minimum: 1 days | Delete artifacts whose blob file has not been modified in this many days. | **Cascading Overridable** |
| `nexus.cleanupPolicies[].criteria.releaseType` | list of string | No | — | — | Delete only specific release types: e.g., `SNAPSHOT` to remove all snapshot versions. | **Cascading Overridable** |
| `nexus.cleanupPolicies[].criteria.regex` | string | No | — | — | Delete artifacts whose names match this regular expression. Advanced filter for custom cleanup. | **Cascading Overridable** |
| `nexus.cleanupPolicies[].criteria.formatAssetRegex` | string | No | — | — | Like regex above, but matches against the full asset path within a format. | **Cascading Overridable** |

---

## 3. `branchGovernance` Block — Branch Rules

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `branchGovernance.includePatterns` | list of string | No | — | Each is a regex pattern | List of allowed branch name patterns. Only branches matching these patterns will be built. Example: `^main$`, `^release/.*`, `^feature/.*`. | **Local Only** |
| `branchGovernance.excludePatterns` | list of string | No | — | Each is a regex pattern | List of branch name patterns to exclude, even if they match include patterns. Deny-list takes priority. | **Local Only** |
| `branchGovernance.protectedBranches` | list of string | No | — | Exact branch names | Branches that require special enforcement rules. Jenkins will not allow direct pushes that bypass pull request checks on these branches. Example: `main`, `release/1.0`. | **Local Only** |

---

## 4. `rbac` Block — Permissions and Teams

### `rbac.roles[]`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `rbac.roles[].name` | string | **Yes** | — | — | The name of a permission role. Examples: `developer`, `admin`, `viewer`. | **Local Only** |
| `rbac.roles[].permissions` | list of string | **Yes** | — | Allowed: `read`, `build`, `cancel`, `configure`, `admin` | What someone with this role is allowed to do. `read` = view jobs; `build` = start builds; `cancel` = stop builds; `configure` = change settings; `admin` = full control. | **Local Only** |

### `rbac.folderAuth`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `rbac.folderAuth.useFolderBasedAuth` | boolean | No | `true` | — | If enabled, Jenkins uses per-folder permission rules instead of global-only permissions. Leave as `true` for multi-team setups. | **Local Only** |
| `rbac.folderAuth.admins` | list of string | No | — | Jenkins usernames | List of Jenkins users who are administrators of this project's folder. They get full control. | **Local Only** |
| `rbac.folderAuth.developers` | list of string | No | — | Jenkins usernames | List of Jenkins users who can view, build, and cancel jobs but cannot change project settings. | **Local Only** |

### `rbac.teams[]`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `rbac.teams[].name` | string | **Yes** | — | Pattern: `^[a-z0-9]([a-z0-9-]*[a-z0-9])?$` | The team name. Lowercase, with hyphens. Example: `backend-team`. | **Local Only** |
| `rbac.teams[].namespace` | string | No | — | — | A grouping label for organizing teams. Example: `us-east` or `europe`. | **Local Only** |
| `rbac.teams[].members` | list of string | No | — | Jenkins usernames | List of Jenkins users who belong to this team. | **Local Only** |
| `rbac.teams[].crossTeamAccess` | list of string | No | — | Team names | Other teams that are allowed to access this team's project resources. Useful when teams need to share artifacts. | **Local Only** |

---

## 5. `maven` Block — Build Configuration

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `maven.pomPath` | string | No | `pom.xml` | — | Path to your Maven Project Object Model file, relative to the repository root. Change this if your POM is in a subdirectory like `module/pom.xml`. | **Local Only** |
| `maven.settingsTemplate` | string | No | — | — | A custom Maven settings template file path. The platform generates settings dynamically; you only need this if you have special Maven server configurations. | **Local Only** |
| `maven.mirrorOf` | string | No | `*` | — | Tells Maven to mirror all repository requests through Nexus. `*` means every dependency goes through Nexus first. Change only if you need to bypass Nexus for certain repositories. | **Cascading Overridable** |
| `maven.skipTests` | boolean | No | `false` | — | Set to `true` to skip unit tests during the build. Useful for quick compile-only checks. Never set to `true` for release builds. | **Local Only** |
| `maven.skipAssembly` | boolean | No | `false` | — | Set to `true` to skip packaging the final distributable archive (JAR/ZIP). Use when you only need compiled classes, not the distribution package. | **Local Only** |
| `maven.jvmArgs` | string | No | `-Xmx1024m -XX:MaxMetaspaceSize=256m` | — | Extra JVM arguments passed to the Maven build process. Controls memory limits and JVM tuning flags. Increase `-Xmx` for large projects. | **Cascading Overridable** |
| `maven.goals` | list of string | No | — | — | Custom Maven goals to run instead of the default lifecycle. Example: `clean`, `install`, `spotbugs:check`, `deploy`. | **Local Only** |
| `maven.extraArtifacts` | list of string | No | — | File paths | Additional files beyond the main JAR to publish alongside your build. Example: `target/*.war`, `target/classes`. | **Local Only** |

### `maven.assembly`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `maven.assembly.name` | string | No | — | — | The name of the distribution archive (the filename without extension). | **Local Only** |
| `maven.assembly.descriptorRef` | string | No | — | — | Pre-defined assembly descriptor to use. Common values: `bin` (binary distribution), `src` (source distribution), `jar-with-dependencies`. | **Local Only** |
| `maven.assembly.formats` | list of string | No | — | — | Archive formats to produce. Example: `tar.gz`, `zip`, `dir`. | **Local Only** |
| `maven.assembly.fileSets` | list of map | No | — | — | Groups of files to include in the archive (source directories, output directories). | **Local Only** |
| `maven.assembly.dependencySets` | list of map | No | — | — | Rules for unpacking or including dependency JARs in the distribution. | **Local Only** |
| `maven.assembly.moduleSets` | list of map | No | — | — | For multi-module projects — includes outputs from specific sub-modules. | **Local Only** |

### `maven.distributionManagement`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `maven.distributionManagement.snapshotRepository.id` | string | **Yes** (if block present) | — | — | A server ID matching the `<server>` entry in Maven settings. Usually `nexus-snapshots`. | **Local Only** |
| `maven.distributionManagement.snapshotRepository.url` | string | **Yes** (if block present) | — | — | The full Nexus URL for uploading snapshot artifacts. Example: `http://buildos-nexus:8081/repository/my-project-snapshots/`. | **Local Only** |
| `maven.distributionManagement.releaseRepository.id` | string | **Yes** (if block present) | — | — | Server ID for release uploads. Usually `nexus-releases`. | **Local Only** |
| `maven.distributionManagement.releaseRepository.url` | string | **Yes** (if block present) | — | — | The full Nexus URL for uploading release artifacts. Example: `http://buildos-nexus:8081/repository/my-project-releases/`. | **Local Only** |

---

## 6. `security` Block — Vulnerability and Compliance Scanning

### `security.trivy`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `security.trivy.severityThreshold` | string | No | `HIGH` | Allowed: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `UNKNOWN` | The minimum severity level that causes a build failure. `HIGH` means only high and critical vulnerabilities fail the build. | **Cascading Overridable** |
| `security.trivy.vulnerabilityTypes` | list of string | No | — | — | Types of vulnerabilities to scan for. Leave empty to scan all types. | **Cascading Overridable** |
| `security.trivy.scanners` | list of string | No | — | — | Specific Trivy scanners to enable (e.g., `vuln`, `secret`, `config`). | **Cascading Overridable** |
| `security.trivy.timeout` | number | No | — | Minimum: 60 seconds | Time limit for the Trivy scan in seconds. Increase for large codebases. | **Cascading Overridable** |

### `security.dependencyScan`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `security.dependencyScan.enabled` | boolean | No | `true` | — | Turn dependency vulnerability scanning on or off. Set to `false` if you have no external dependencies or scan elsewhere. | **Cascading Overridable** |
| `security.dependencyScan.failOnCritical` | boolean | No | `true` | — | If `true`, the build fails when a critical-severity vulnerability is found in your dependencies. | **Cascading Overridable** |
| `security.dependencyScan.cveThreshold` | number | No | `0` | Minimum: 0 | The maximum number of CVEs (Common Vulnerabilities and Exposures) allowed before the build fails. `0` means zero tolerance. | **Cascading Overridable** |
| `security.dependencyScan.suppressionFile` | string | No | — | — | Path to a file listing CVEs that should be ignored (false positives). Useful when a vendor says a CVE does not affect your use case. | **Cascading Overridable** |

### `security.secretScan`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `security.secretScan.enabled` | boolean | No | `true` | — | Turn secret scanning on or off. When on, the build scans your code for hard-coded passwords, API keys, and tokens. | **Cascading Overridable** |
| `security.secretScan.failOnCritical` | boolean | No | `true` | — | If `true`, the build fails when a potential secret is found in your code. | **Cascading Overridable** |
| `security.secretScan.entropyThreshold` | number | No | `4.5` | Range: 3.0–8.0 | How sensitive the secret detector is. Lower values catch more (including false alarms); higher values miss more but have fewer false positives. | **Cascading Overridable** |
| `security.secretScan.excludePatterns` | list of string | No | — | Regex patterns | File paths or patterns to exclude from secret scanning. Example: `*.md`, `test/**`. | **Cascading Overridable** |
| `security.secretScan.includePatterns` | list of string | No | — | Regex patterns | Only scan files matching these patterns. Leave empty to scan everything. | **Cascading Overridable** |

### `security.sbom`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `security.sbom.enabled` | boolean | No | `true` | — | Turn Software Bill of Materials generation on or off. An SBOM is a machine-readable list of all components in your project. | **Cascading Overridable** |
| `security.sbom.format` | string | No | `cyclonedx` | Allowed: `cyclonedx`, `spdx` | The SBOM format. `cyclonedx` is the modern standard; `spdx` is an alternative used by some compliance frameworks. | **Cascading Overridable** |

### `security.signing`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `security.signing.enabled` | boolean | No | `false` | — | Turn on GPG signing of your build artifacts. Required for publishing to Maven Central. | **Cascading Overridable** |
| `security.signing.keyId` | string | No | — | — | The GPG key ID (fingerprint suffix) used to sign artifacts. Obtain via `gpg --list-keys`. | **Cascading Overridable** |
| `security.signing.passphraseEnvVar` | string | No | `SIGNING_PASSPHRASE` | — | The environment variable name that holds your GPG key passphrase. Jenkins must have this variable set in the build environment. | **Cascading Overridable** |

---

## 7. `quality` Block — Code Quality Gates

### `quality.coverage`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `quality.coverage.tool` | string | No | `jacoco` | Allowed: `jacoco`, `cobertura`, `clover` | Which code coverage tool to use. JaCoCo is the default for modern Java projects. | **Cascading Overridable** |
| `quality.coverage.lineCoverageThreshold` | number | No | `80` | Range: 0–100 | The minimum percentage of lines that must be covered by tests. Build fails if coverage is below this number. Set to 80 for 80%. | **Cascading Overridable** |
| `quality.coverage.branchCoverageThreshold` | number | No | `70` | Range: 0–100 | The minimum percentage of code branches (if/else paths) that must be covered by tests. | **Cascading Overridable** |
| `quality.coverage.failOnBelowThreshold` | boolean | No | `true` | — | If `true`, the build fails when coverage drops below the thresholds above. Set to `false` to report coverage without failing the build (useful during migration). | **Cascading Overridable** |

### `quality.sonarQube`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `quality.sonarQube.hostUrl` | string | No | — | Pattern: `^https?://.*` | The web address of your SonarQube server. Example: `http://buildos-sonarqube:9000`. | **Global Only** |
| `quality.sonarQube.token` | string | No | — | — | The SonarQube authentication token. You can put it here directly, or (safer) store it in Jenkins as a secret text credential and reference it by environment variable. | **Local Only** |
| `quality.sonarQube.projectKey` | string | No | — | — | A unique key for your project in SonarQube. Usually matches your project name. Example: `com.mycompany:my-service`. | **Local Only** |
| `quality.sonarQube.qualityGateTimeoutMs` | number | No | `300000` | Minimum: 10000 | How long (in milliseconds) Jenkins waits for SonarQube to finish analyzing your code. Default is 5 minutes (300,000 ms). | **Cascading Overridable** |
| `quality.sonarQube.qualityGatePollIntervalMs` | number | No | `5000` | Minimum: 1000 | How often (in milliseconds) Jenkins checks back with SonarQube to see if analysis is done. Default is every 5 seconds. | **Cascading Overridable** |

---

## 8. `observability` Block — Monitoring, Logging, and Tracing

### `observability.telemetry`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `observability.telemetry.endpoint` | string | No | — | Pattern: `^https?://.*` | The URL where build telemetry data (timing, success/failure rates) is sent. Leave blank if you do not have a telemetry collector. | **Cascading Overridable** |
| `observability.telemetry.enabled` | boolean | No | `true` | — | Turn telemetry collection on or off. Telemetry helps the platform team understand build performance trends. | **Cascading Overridable** |

### `observability.audit`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `observability.audit.enabled` | boolean | No | `true` | — | Turn audit logging on or off. When on, every build action (who ran it, when, what changed) is recorded for compliance. | **Cascading Overridable** |
| `observability.audit.logLevel` | string | No | `INFO` | Allowed: `DEBUG`, `INFO`, `WARN`, `ERROR` | How detailed the audit logs are. `INFO` records normal operations; `DEBUG` adds verbose detail for troubleshooting; `ERROR` only records failures. | **Cascading Overridable** |

### `observability.opentelemetry`

| Parameter Path | Type | Required | Default | Constraints / Regex | Non-Technical Description | Scope |
|---|---|---|---|---|---|---|
| `observability.opentelemetry.endpoint` | string | No | — | Pattern: `^https?://.*` | The URL of your OpenTelemetry collector (e.g., Jaeger or Grafana Tempo). Used for distributed tracing across the build pipeline. | **Cascading Overridable** |
| `observability.opentelemetry.enabled` | boolean | No | `true` | — | Turn distributed tracing on or off. Traces show you exactly where time is spent during a build. | **Cascading Overridable** |
| `observability.opentelemetry.serviceName` | string | No | — | — | The name that appears in your tracing dashboard for this project's build traces. Example: `demo-java-service-build`. | **Cascading Overridable** |

---

## Quick-Reference: Required vs. Optional by Section

| Section | Required fields | Everything else |
|---|---|---|
| `project` | `projectName`, `businessUnit`, `team`, `repositoryUrl` | All optional with defaults |
| `nexus` | `url` (if section present) | All optional with defaults |
| `branchGovernance` | None | All optional |
| `rbac` | `roles[].name`, `roles[].permissions` (if section present) | All optional |
| `maven` | `snapshotRepository.id/url` + `releaseRepository.id/url` (if distributionManagement present) | All optional with defaults |
| `security` | None | All optional with defaults |
| `quality` | None | All optional with defaults |
| `observability` | None | All optional with defaults |

---

## Reference: Complete Minimal `project.yml`

```yaml
version: "1.0"
project:
  metadata:
    projectName: "your-app-name"
    businessUnit: "YourBusinessUnit"
    team: "your-team"
    repositoryUrl: "http://buildos-git:3000/your-org/your-app.git"
```

All other sections are optional and merge with the platform's cascading config
hierarchy: **Base Framework Defaults ← `global.yml` ← `project.yml` (highest priority)**.

---

*End of PARAMETERS.MD — BuildOS Platform Parameters Reference v2.1*
