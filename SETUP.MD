# BuildOS Platform — Complete Infrastructure Setup Guide

> **Audience:** Non-technical operators, DevOps newcomers, and anyone who needs to
> get the entire 4-node ecosystem running from scratch on a single machine.

---

## 1. What This System Does — The Big Picture

The BuildOS Platform automates enterprise Java builds. Four services work together:

```
  ┌──────────────┐       webhook       ┌──────────────┐
  │    Gitea     │─────┬──────────────►│   Jenkins    │
  │  (Git Host)  │     │               │  (CI/CD)     │
  │   :3000      │◄────┘               │   :8080      │
  └──────────────┘   git push           └──────┬───────┘
                                               │
                     ┌─────────────────────────┼─────────────┐
                     │                         │             │
                     ▼                         ▼             ▼
              ┌──────────────┐          ┌──────────────┐
              │  SonarQube   │          │    Nexus     │
              │ Code Quality │          │  Artifacts   │
              │   :9000      │          │   :8081      │
              └──────────────┘          └──────────────┘
```

### What each service does

| Service  | Port  | Job |
|----------|-------|-----|
| **Gitea**    | `:3000` | Hosts your source code. Developers `git push` here. |
| **Jenkins**  | `:8080` | Runs the automated pipeline — compiles, tests, scans, publishes. |
| **SonarQube**| `:9000` | Inspects code for bugs, vulnerabilities, and test coverage. |
| **Nexus**    | `:8081` | Stores build artifacts (JARs, WARs) and proxies Maven Central. |

### How code flows through the system

1. **Developer pushes code** to Gitea (`git push`).
2. Gitea sends a **webhook notification** to Jenkins.
3. Jenkins **pulls the latest code** from Gitea.
4. Jenkins **compiles** the code, **runs tests**, then sends the code to **SonarQube** for analysis.
5. SonarQube **scores the code** and reports back to Jenkins.
6. Jenkins **packages** the artifact and **uploads it to Nexus**.
7. Jenkins **records metrics** (how long it took, what passed/failed).

---

## 2. Prerequisites — What You Need on Your Machine

- **Docker Desktop** (or Docker Engine + docker-compose-plugin)
- **Git** (`git --version` should work)
- A **terminal** (Command Prompt, PowerShell, or Bash)
- 8 GB RAM free (the stack uses ~6 GB total)

---

## 3. Quick-Start — One Command to Start Everything

```bash
# From the project root directory:
docker compose down -v && docker compose up -d --build
```

**Wait 3–5 minutes** for all services to fully boot. You can watch logs with:

```bash
docker compose logs --tail=50 -f
```

When you see steady log output (no more crash-restart loops), proceed.

---

## 4. Service-by-Service Setup Checklist

### 4.1 Gitea — Source Code Host (`http://localhost:3000`)

1. Open your browser to **http://localhost:3000**.
2. The **first visit** shows the Gitea installation page. Fill in:

   | Field | Value |
   |-------|-------|
   | Site Title | `BuildOS Git` |
   | Database Type | **SQLite3** (default, no config needed) |
   | Gitea HTTP Listen Port | `3000` |
   | Server Domain | `localhost` |
   | Gitea Base URL | `http://localhost:3000/` |

3. Click **"Install Gitea"** (takes ~10 seconds).
4. Register the admin account:

   | Field | Value |
   |-------|-------|
   | Username | `gitea_admin` |
   | Password | `admin123` |
   | Email | `admin@buildos.local` |

5. **Create a new repository** for your project:
   - Click `+` → **"New Repository"**
   - Repository name: `demo-java-service`
   - Visibility: **Private** (or Public — your choice)
   - Click **"Create Repository"**
6. **Push your code** to this new repo:
   ```bash
   cd /path/to/your/project
   git remote add origin http://localhost:3000/gitea_admin/demo-java-service.git
   git push -u origin main
   ```
   Use username `gitea_admin` / password `admin123` when prompted.

---

### 4.2 Jenkins — Automation Engine (`http://localhost:8080`)

1. Open **http://localhost:8080**.
2. **Unlock Jenkins** — get the temporary admin password:

   ```bash
   docker exec buildos-jenkins cat /var/jenkins_home/secrets/initialAdminPassword
   ```

3. Paste that password into the browser field and click **"Continue"**.
4. On **"Customize Jenkins"**, choose **"Install suggested plugins"** (waits 2–3 minutes).
5. Create the admin user:

   | Field | Value |
   |-------|-------|
   | Username | `aadu` |
   | Password | `password123` |
   | Confirm Password | `password123` |
   | Full Name | `Platform Admin` |
   | Email | `aadu@buildos.local` |

6. **Instance Configuration:** Jenkins URL should be `http://localhost:8080/` — click **"Save and Finish"**.

---

### 4.3 SonarQube — Code Quality Gate (`http://localhost:9000`)

1. Open **http://localhost:9000**.
2. Log in with:

   | Field | Value |
   |-------|-------|
   | Username | `admin` |
   | Password | `admin` |

3. SonarQube asks you to **reset the password**:
   - New password: `admin` (yes, same value — just confirm it again)
4. **Generate a token** for Jenkins to use:
   - Click your avatar (top-right) → **"My Account"** → **"Security"**
   - Under **"Generate Tokens"**, enter: `buildos-jenkins-token`
   - Click **"Generate"**
   - **COPY THE TOKEN NOW** — it looks like `sqp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`
   - You will paste this into Jenkins in step 5.3 below.

---

### 4.4 Nexus — Artifact Repository (`http://localhost:8081`)

1. Open **http://localhost:8081**.
2. Get the temporary admin password:

   ```bash
   docker exec buildos-nexus cat /nexus-data/admin.password
   ```

3. Log in with username `admin` and the password from step 2.
4. Nexus immediately asks to **reset the password**:
   - Enter the temporary password from step 2
   - New password: `admin123`
   - Confirm: `admin123`
5. Click **"Next"** (skip the anonymous access question — default is fine).
6. **Done.** Nexus is ready.

---

## 5. Jenkins Credential Keys — Copy-Paste Walkthrough

Jenkins needs three credential entries so it can talk to Gitea, SonarQube, and Nexus.

### 5.1 Create the `git` credential (SCM access)

1. From the Jenkins dashboard: **Dashboard** → **Manage Jenkins** → **Credentials**.
2. Click on **"System"** → then **"Global credentials (unrestricted)"**.
3. Click **"Add Credentials"** (left sidebar or top-right).
4. Fill in the form:

   | Field | Value |
   |-------|-------|
   | Kind | **Username with password** |
   | Scope | **Global** |
   | Username | `gitea_admin` |
   | Password | `admin123` |
   | ID | **`git`** |
   | Description | `Gitea SCM credentials` |

5. Click **"Create"**.

### 5.2 Create the `enterprise-nexus-credentials` key (Nexus access)

1. **Dashboard** → **Manage Jenkins** → **Credentials** → **System** → **Global credentials**.
2. **"Add Credentials"**:

   | Field | Value |
   |-------|-------|
   | Kind | **Username with password** |
   | Scope | **Global** |
   | Username | `admin` |
   | Password | `admin123` |
   | ID | **`enterprise-nexus-credentials`** |
   | Description | `Nexus admin credentials for artifact upload` |

3. Click **"Create"**.

### 5.3 Create the `sonar-auth-token` key (SonarQube access)

1. **Dashboard** → **Manage Jenkins** → **Credentials** → **System** → **Global credentials**.
2. **"Add Credentials"**:

   | Field | Value |
   |-------|-------|
   | Kind | **Secret text** |
   | Scope | **Global** |
   | Secret | Paste the `sqp_...` token you copied in section 4.3 step 4 |
   | ID | **`sonar-auth-token`** |
   | Description | `SonarQube authentication token` |

3. Click **"Create"**.

---

## 6. Gitea → Jenkins Webhook — Auto-Trigger Builds

Jenkins must know when new code is pushed.

1. In **Gitea**, go to your repository → **Settings** → **Webhooks** → **"Add Webhook"** → **"Gitea"**.
2. Fill in:

   | Field | Value |
   |-------|-------|
   | Target URL | `http://buildos-jenkins:8080/git/notifyCommit?url=http://buildos-git:3000/gitea_admin/demo-java-service.git` |
   | HTTP Method | **POST** |
   | Trigger On | **Push events** |
   | Active | **Checked** |

3. Click **"Add Webhook"**.

> **Why `buildos-jenkins` and not `localhost`?**
> Docker containers see each other by their service names (defined in
> `docker-compose.yml`). `localhost` inside a container refers to that container
> itself, not your host machine. DNS names like `buildos-jenkins` and
> `buildos-git` resolve to the correct container IPs on the Docker network.

---

## 7. Slim `project.yml` — Single Source of Truth Pattern

With the SSoT architecture, your application's `project.yml` contains only identity
metadata. Infrastructure URLs, scanner defaults, and quality thresholds live in the
central `resources/platform/global.yml` file. This lets you change system-wide settings
without touching any application code.

**Your app's `project.yml`** (minimal — only identity + branch rules):

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
branchGovernance:
  includePatterns:
    - "^main$"
    - "^feature/.*"
```

That is all the app needs. The rest is inherited from the platform.

### 7.1 How the Config Merge Works

```
Priority 1 (lowest):  Base Framework Defaults  (hard-coded in Groovy classes)
Priority 2 (medium):  resources/platform/global.yml  (SSoT — infrastructure, policies)
Priority 3 (highest): <app>/project.yml               (per-app overrides)
```

The system reads all three layers at pipeline startup and merges them. Your `project.yml`
overrides only the fields you explicitly set; everything else falls through to `global.yml`
then to framework defaults.

### 7.2 The Central `global.yml` File

Location: `resources/platform/global.yml` (managed by platform team — one copy for all projects)

```yaml
version: "1.0"
environment: "local-dev-simulation"
infrastructure:
  gitea: "http://buildos-git:3000"
  jenkins: "http://buildos-jenkins:8080"
  sonarQube:
    serverUrl: "http://buildos-sonarqube:9000"
    installationName: "SonarQube"
  nexus:
    serverUrl: "http://buildos-nexus:8081"
    credentialsId: "enterprise-nexus-credentials"
security_defaults:
  trivy:
    severityThreshold: "HIGH"
    timeout: 300
  secretScan:
    enabled: true
    entropyThreshold: 4.5
  dependencyScan:
    enabled: true
    failOnCritical: true
quality_governance:
  coverage:
    tool: "jacoco"
    lineCoverageThreshold: 80
    branchCoverageThreshold: 70
    failOnBelowThreshold: true
  sonarQube:
    qualityGateTimeoutMs: 300000
    qualityGatePollIntervalMs: 5000
```

**How to change a system-wide setting:** Edit `global.yml`. For example, to raise
the coverage bar to 85%, change `lineCoverageThreshold: 80` to `lineCoverageThreshold: 85`.
All applications pick up the new value on their next build — no code changes needed.

**How to override for one app:** Add the same key to that app's `project.yml`. For example,
if `demo-java-service` needs a lower threshold, add:
```yaml
quality:
  coverage:
    lineCoverageThreshold: 60
```
This overrides the global value for this app only.

### 7.3 What Belongs Where

| Layer | File | Who manages it | Example contents |
|---|---|---|---|
| Base defaults | Groovy source (`src/`) | Platform team | Fallback values when nothing else is set |
| Global SSoT | `resources/platform/global.yml` | Platform/DevOps team | Infra URLs, security policies, quality gates |
| App overrides | `<app-root>/project.yml` | Application team | Project name, team, branch rules, per-app overrides |

---

## 8. Multi-Repository Decoupling (App ←→ Platform Separation)

The platform framework library and your application code live in **completely separate
Git repositories**. The platform repo (`ent-dsl-platform-core`) contains only Groovy
pipeline code, schemas, and Docker config — zero application source files. Each
microservice gets its own standalone repository with its own `project.yml`.

### 8.1 Architecture Overview

```
┌──────────────────────────────────┐     ┌──────────────────────────────┐
│  Platform Library (this repo)    │     │  Application Repos (separate)│
│                                  │     │                              │
│  /workspace/                     │     │  /tmp/demo-python-service/   │
│  ├── vars/ (pipeline macros)     │     │  ├── main.py                 │
│  ├── src/ (Groovy classes)       │     │  ├── requirements.txt        │
│  ├── resources/platform/         │     │  └── project.yml (slim)      │
│  │   └── global.yml (SSoT)       │     │                              │
│  ├── resources/schemas/          │     │  /tmp/demo-node-service/     │
│  └── docker-compose.yml          │     │  ├── app.js                  │
│                                  │     │  ├── package.json            │
│  No application code here!       │     │  └── project.yml (slim)      │
└──────────────────────────────────┘     └──────────────────────────────┘
```

### 8.2 How to Onboard a New Microservice

**Step 1 — Create a blank repository in Gitea.**

1. Log into Gitea at **http://localhost:3000**.
2. Click **"+"** → **"New Repository"**.
3. Repository name: `demo-python-service` (matches `projectName`).
4. Visibility: **Private**.
5. Do **not** check "Initialize this repository" — you will push local content.
6. Click **"Create Repository"**.

**Step 2 — Prepare your local project folder.**

```bash
# Scaffold your app and a slim project.yml anywhere (e.g., /tmp/):
mkdir /tmp/demo-python-service
cd /tmp/demo-python-service

# Create main.py, requirements.txt (your application code)
# Create project.yml with ONLY metadata identity:

cat > project.yml << 'EOF'
version: "1.0"
project:
  metadata:
    projectName: "demo-python-service"
    businessUnit: "PlatformEngineering"
    team: "core-devops"
    repositoryUrl: "http://buildos-git:3000/aadu/demo-python-service.git"
    scmCredentialsId: "git"
    description: "Python FastAPI Microservice App Archetype Profile"
EOF
```

**Step 3 — Initialize Git and push.**

```bash
git init
git branch -m main
git add .
git commit -m "chore: initialise microservice scaffold"
git remote add origin http://localhost:3000/aadu/demo-python-service.git
git push -u origin main
```

Use Gitea credentials (`gitea_admin` / `admin123`) when prompted for HTTP auth.

**Step 4 — Configure the Gitea → Jenkins webhook.**

In Gitea: **Repo → Settings → Webhooks → Add Webhook → Gitea**.

| Field | Value |
|-------|-------|
| Target URL | `http://buildos-jenkins:8080/git/notifyCommit?url=http://buildos-git:3000/aadu/demo-python-service.git` |
| HTTP Method | `POST` |
| Content Type | `application/json` |
| Trigger | Push events |
| Active | Checked |

**Step 5 — Jenkins auto-discovers the new project.**

The Jenkins Multibranch Pipeline scans the repository, reads `project.yml`,
inherits infrastructure URLs from `global.yml`, and runs the 50-stage lifecycle.
No changes to the platform library are needed.

### 8.3 What Stays in the Platform Library vs. the App Repo

| Layer | Platform Library (`ent-dsl-platform-core`) | App Repository (`demo-*-service`) |
|---|---|---|
| Pipeline logic | `vars/`, `src/`, `Jenkinsfile.platform` | — |
| Global config | `resources/platform/global.yml` | — |
| Schema | `resources/schemas/project-schema.yml` | — |
| Docker stack | `docker-compose.yml`, `jenkins.yaml` | — |
| App source code | **Never** | `main.py` / `app.js`, etc. |
| App identity | — | `project.yml` (slim — metadata only) |
| Build artifacts | — | `target/`, `dist/`, `node_modules/` |

### 8.4 Benefits of Decoupling

- **No cross-contamination**: Application code never pollutes the shared library repo.
- **Independent versioning**: Each microservice versions and releases on its own cadence.
- **Platform upgrades**: Update the library once — all consuming repos get the new
  pipeline behavior on their next build (no app code changes).
- **Polyglot freedom**: Python, Node.js, Java, Go — each service uses its own language
  and toolchain. The `project.yml` parser is language-agnostic.

---

## 9. Service Port Reference Card

| Service   | Host Port | Internal Port | Container Name       | URL (from browser)          | URL (from other containers)       |
|-----------|-----------|---------------|----------------------|-----------------------------|-----------------------------------|
| Gitea     | `:3000`   | `:3000`       | `buildos-git`        | http://localhost:3000        | http://buildos-git:3000           |
| Jenkins   | `:8080`   | `:8080`       | `buildos-jenkins`    | http://localhost:8080        | http://buildos-jenkins:8080       |
| SonarQube | `:9000`   | `:9000`       | `buildos-sonarqube`  | http://localhost:9000        | http://buildos-sonarqube:9000     |
| Nexus     | `:8081`   | `:8081`       | `buildos-nexus`      | http://localhost:8081        | http://buildos-nexus:8081         |

---

## 10. Troubleshooting Guide

### 10.1 "Trailing slash" errors in webhook URLs
### 10.2 Shell "dangling quote" trap
### 10.3 Nexus "Connection refused" from Jenkins
### 10.4 Webhook does not trigger Jenkins
### 10.5 Docker container keeps restarting
### 10.6 Jenkins credentials ID mismatch

Pipeline error: `git not found` or `enterprise-nexus-credentials not found`?

1. Go to **Jenkins → Manage Jenkins → Credentials → System → Global credentials**.
2. Verify the **ID** column shows exactly `git`, `sonar-auth-token`, and
   `enterprise-nexus-credentials`.
3. IDs are **case-sensitive** — `Git` is different from `git`.

---

## 11. Reset Everything (Clean Slate)

If something is broken beyond repair, wipe everything and start fresh:

```bash
# WARNING: This destroys ALL data (repos, build history, artifacts)
docker compose down -v && docker compose up -d --build
```

The `-v` flag deletes Docker volumes (databases, Nexus blob store, Jenkins home).
You will need to redo sections 4.1 through 5.3 after a full reset.

---

## 12. Quick Reference — Docker Commands

| Action | Command |
|--------|---------|
| Start all services | `docker compose up -d` |
| Stop all services | `docker compose down` |
| Full reset (destroy volumes) | `docker compose down -v && docker compose up -d --build` |
| View all logs | `docker compose logs -f` |
| View logs for one service | `docker compose logs -f <service>` |
| List running containers | `docker ps` |
| Shell into a container | `docker exec -it <container-name> bash` |
| Jenkins admin password | `docker exec buildos-jenkins cat /var/jenkins_home/secrets/initialAdminPassword` |
| Nexus admin password | `docker exec buildos-nexus cat /nexus-data/admin.password` |

---

*End of SETUP.MD — BuildOS Platform Infrastructure Guide v1.0*
