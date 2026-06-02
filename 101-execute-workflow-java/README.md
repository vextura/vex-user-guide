# 101 — Execute a Workflow with the Java SDK

**SDK:** `ai.vextura:uwf-engine-sdk-java:1.2.2`

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21+ |
| Maven | 3.9+ |

No `vexctl` required for application code — auth is handled by the SDK automatically.

---

## What your Vextura admin gives you

Before running anything your admin provides four values:

| Variable | Description | Example |
|----------|-------------|---------|
| `VEX_AUTH_URL` | vex-auth service URL | `http://vex-auth.your-cluster.internal:8095` |
| `VEX_CLIENT_ID` | Your M2M client ID | `my-app` |
| `VEX_CLIENT_SECRET` | Your M2M client secret | `abc123...` |
| `UWF_ENDPOINT` | Workflow engine URL | `http://uwf-engine.your-cluster.internal:8080` |

---

## How authentication works

The SDK uses the **OAuth2 `client_credentials` grant**. You never manage tokens manually:

```
Your app           vex-auth                vex-engine
    │                  │                        │
    │── POST /auth/token ──────────────────────>│
    │   grant_type=client_credentials           │
    │   client_id + client_secret               │
    │<── { access_token, expires_in: 3600 } ────│
    │                                           │
    │── GET /api/v1/workflows ── Bearer <token>──>│
    │                                           │
    │  (token auto-refreshes 60s before expiry) │
```

`M2MAuth` caches the token and refreshes it automatically — no manual refresh needed.

---

## Step 1 — Add the dependency

**Maven:**
```xml
<dependency>
    <groupId>ai.vextura</groupId>
    <artifactId>uwf-engine-sdk-java</artifactId>
    <version>1.2.2</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'ai.vextura:uwf-engine-sdk-java:1.2.2'
```

---

## Step 2 — Create the client

```java
import ai.vextura.uwf_engine.UwfEngineClient;
import ai.vextura.uwf_engine.runtime.M2MAuth;

// M2MAuth fetches the JWT on first use and refreshes automatically before expiry.
// All four values come from your Vextura admin — never hardcode them.
M2MAuth auth = new M2MAuth(
    System.getenv("VEX_AUTH_URL"),
    System.getenv("VEX_CLIENT_ID"),
    System.getenv("VEX_CLIENT_SECRET")
);

UwfEngineClient client = UwfEngineClient.withEndpoint(
    System.getenv("UWF_ENDPOINT"),
    auth
);
```

---

## Step 3 — Execute a workflow

```java
import ai.vextura.uwf_engine.models.*;
import java.util.Map;

ExecuteWorkflowInput req = new ExecuteWorkflowInput();
req.workflowId = "kaspi-payment-v1";
req.inputData  = Map.of(
    "amount",   1000,
    "currency", "KZT",
    "sender",   "my-app"
);

ExecutionResult result = client.executeWorkflow(req);
System.out.println("Run ID: " + result.runId);
System.out.println("Status: " + result.status);   // "pending" or "completed"
```

---

## Step 4 — Poll for completion

```java
RunIdInput statusReq = new RunIdInput();
statusReq.runId = result.runId;

for (int i = 0; i < 30; i++) {
    ExecutionStatus s = client.getExecutionStatus(statusReq);
    System.out.println(s.status);  // pending → running → completed/failed
    if ("completed".equals(s.status) || "failed".equals(s.status)) break;
    Thread.sleep(1_000);
}
```

---

## Step 5 — Run the full example

```bash
# Clone this repo
git clone https://github.com/vextura/vex-user-guide.git
cd vex-user-guide/101-execute-workflow-java

# Set the four values your admin gave you
export VEX_AUTH_URL=http://vex-auth.your-cluster.internal:8095
export VEX_CLIENT_ID=my-app
export VEX_CLIENT_SECRET=your-secret
export UWF_ENDPOINT=http://uwf-engine.your-cluster.internal:8080
export WORKFLOW_ID=kaspi-payment-v1

# Build and run
mvn compile exec:java
```

Expected output:
```
Engine status : healthy
NATS          : true
Redis         : true
Workflows     : 3 registered
  • kaspi-payment-v1 — Kaspi Payment Fraud Detection
  • kaspi-payment-flow — Kaspi Payment Approval Flow
  • truckpay-settlement — TruckPay Settlement Processing

Executing workflow : kaspi-payment-v1
Run ID             : run-1780371635657067673
Initial status     : pending
  [ 1s] status=running      step=fraud-check
  [ 2s] status=running      step=score-eval
  [ 3s] status=completed    step=null

Final status       : completed
```

---

## SDK reference

| Method | Description |
|--------|-------------|
| `client.healthCheck()` | Check engine + NATS + Redis status |
| `client.listWorkflows()` | List all registered workflow definitions |
| `client.getWorkflow(req)` | Get a single workflow definition by ID |
| `client.executeWorkflow(req)` | Execute synchronously, returns runId |
| `client.asyncExecuteWorkflow(req)` | Execute without waiting |
| `client.getExecutionStatus(req)` | Poll execution status by runId |
| `client.listExecutions(req)` | List past executions (paginated) |
| `client.cancelExecution(req)` | Cancel a running execution |

---

## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `VEX_AUTH_URL is required` | Env var not set | Set all four env vars from your admin |
| `M2MAuth: token request failed (400)` | Wrong grant or bad credentials | Verify `VEX_CLIENT_ID` / `VEX_CLIENT_SECRET` with your admin |
| `M2MAuth: token request failed (401)` | Invalid credentials | Re-confirm credentials with your admin |
| `Connection refused on UWF_ENDPOINT` | Wrong URL or no network access | Confirm you're on the cluster network and `UWF_ENDPOINT` is correct |
| `404 workflow not found` | Wrong workflow ID | Call `client.listWorkflows()` to see registered IDs |

---

## Next guides

- **102** — Execute a Workflow — Python SDK *(coming soon)*
- **103** — Execute a Workflow — Go SDK *(coming soon)*
- **104** — Deploy a New Service *(coming soon)*
- **105** — Set Up a CI/CD Pipeline *(coming soon)*
