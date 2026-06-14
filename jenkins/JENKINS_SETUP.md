# Jenkins CI/CD Setup Guide

## 1. Start Jenkins (Docker-based, no Windows install needed)

```bash
docker compose -f jenkins/docker-compose.jenkins.yml up -d
```

Open http://localhost:8090 and get the initial password:
```bash
docker exec fraudshield-jenkins \
  cat /var/jenkins_home/secrets/initialAdminPassword
```

Install suggested plugins when prompted. Create admin user.

---

## 2. Configure Tools (Manage Jenkins → Tools)

**JDK:**
- Name: `JDK-21`
- Install automatically: Yes → Adoptium → JDK 21 (latest)

**Maven:**
- Name: `Maven-3.9`
- Install automatically: Yes → 3.9.6

---

## 3. Install Required Plugins (Manage Jenkins → Plugins)

| Plugin | Purpose |
|--------|---------|
| Pipeline | Declarative pipeline support |
| Git | Source checkout |
| GitHub Branch Source | PR auto-detection |
| Docker Pipeline | `docker.build()` steps |
| Warnings Next Generation | Checkstyle + SpotBugs reports |
| JaCoCo | Coverage reports in Jenkins |
| JUnit | Test results graphs |
| Credentials | Storing secrets securely |

---

## 4. Create Multibranch Pipeline

1. New Item → **Multibranch Pipeline** → Name: `fraudshield`
2. Branch Sources → **GitHub**
   - Credentials: Add → Username + Personal Access Token
   - Repository URL: `https://github.com/Cathy0326/FraudShield`
3. Scan Multibranch Pipeline Triggers: **1 minute**
4. Save → Jenkins auto-discovers branches and PRs

Every PR will now trigger the pipeline automatically.

---

## 5. Add GitHub Webhook (instant triggers)

GitHub repo → Settings → Webhooks → Add webhook:
- Payload URL: `http://YOUR_LOCAL_IP:8090/github-webhook/`
  (use your machine's LAN IP, not localhost)
- Content type: `application/json`
- Events: **Pull requests** + **Pushes**

> **Tip:** Use [ngrok](https://ngrok.com) if Jenkins is behind NAT:
> `ngrok http 8090` → use the https URL as webhook payload URL

---

## 6. Add Credentials for Cloud Deployment

Manage Jenkins → Credentials → System → Global → Add Credential:

| ID | Type | Description |
|----|------|-------------|
| `docker-registry-password` | Secret text | ACR password or ECR (via IAM role) |
| `azure-openai-key` | Secret text | Azure OpenAI API key |
| `jwt-secret` | Secret text | JWT signing secret |

---

## 7. Pipeline Stages Summary

```
PR opened / push to branch
        │
        ▼
  ┌─────────────┐
  │  Checkout   │
  └──────┬──────┘
         ▼
  ┌──────────────────────────┐
  │  Code Quality (parallel) │
  │  Checkstyle │  SpotBugs  │ ← fails build if violations found
  └──────────────────────────┘
         ▼
  ┌───────────────────────────────┐
  │  Unit Tests (49 tests)        │
  │  JaCoCo coverage ≥ 70% gate  │
  └──────┬────────────────────────┘
         ▼
  ┌─────────────────────────────┐
  │  Integration Tests (11 ITs) │
  └──────┬──────────────────────┘
         ▼
  ┌─────────────┐
  │  Build JAR  │
  └──────┬──────┘
         ▼
  ┌──────────────────────┐
  │  Build Docker Images │
  │  backend + frontend  │
  └──────┬───────────────┘
         ▼ (main branch only)
  ┌──────────────────────┐
  │  Push to Registry    │ → Azure ACR or AWS ECR
  └──────┬───────────────┘
         ▼
  ┌──────────────────────┐
  │  Deploy              │ → Azure Container Apps / AWS EC2 / local
  └──────────────────────┘
```

---

## 8. Quick Commands

```bash
# Start Jenkins
docker compose -f jenkins/docker-compose.jenkins.yml up -d

# Stop Jenkins
docker compose -f jenkins/docker-compose.jenkins.yml down

# View logs
docker logs fraudshield-jenkins -f

# Run pipeline locally (without Jenkins):
mvn checkstyle:check          # Code quality
mvn test                      # Unit tests (49)
mvn failsafe:integration-test # Integration tests (11)
mvn verify                    # Everything including JaCoCo + SpotBugs
./deploy/deploy-azure.sh      # Deploy to Azure
```
