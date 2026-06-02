# 101 — Execute a Workflow with the Java SDK

This guide shows how to connect to a running Vextura cluster, list registered workflows,
execute one, and poll until it completes — using the official `uwf-engine-sdk-java` SDK.

**SDK version used:** `1.2.2` (latest on Maven Central)

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 21+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| vexctl | any | [Install guide](https://github.com/vextura/vexctl) |
| Vextura cluster access | — | Dev, staging, or production |

---

## Step 1 — Add the dependency

**Maven (`pom.xml`)**

```xml
<dependency>
    <groupId>ai.vextura</groupId>
    <artifactId>uwf-engine-sdk-java</artifactId>
    <version>1.2.2</version>
</dependency>
```

**Gradle (`build.gradle`)**

```groovy
implementation 'ai.vextura:uwf-engine-sdk-java:1.2.2'
```

---

## Step 2 — Resolve your endpoint

The SDK connects to the workflow engine via RIP — no hardcoded URLs.

```bash
# Resolve the workflow engine endpoint for your region
export UWF_ENDPOINT=$(vexctl rip resolve uwf-engine <your-region>)

# Verify
echo $UWF_ENDPOINT
# → http://10.x.x.x:8080  (or a DNS name inside the cluster)
```

> **Regions:** `kz-north-1`, `local-dev`, or whatever your cluster is configured with.
> Run `vexctl rip list` to see all registered services and regions.

---

## Step 3 — Get an auth token

```bash
export VEX_TOKEN=$(vexctl auth token)
```

Tokens expire — re-run this if you get `401` responses.

---

## Step 4 — Create a client and execute a workflow

```java
import ai.vextura.uwf_engine.UwfEngineClient;
import ai.vextura.uwf_engine.models.*;
import ai.vextura.uwf_engine.runtime.BearerAuth;
import java.util.Map;

// 1. Create client — endpoint resolved via RIP, never hardcoded
UwfEngineClient client = UwfEngineClient.withEndpoint(
    System.getenv("UWF_ENDPOINT"),
    new BearerAuth(System.getenv("VEX_TOKEN"))
);

// 2. Execute a workflow
ExecuteWorkflowInput req = new ExecuteWorkflowInput();
req.workflowId = "kaspi-payment-v1";
req.inputData  = Map.of(
    "amount",   1000,
    "currency", "KZT",
    "sender",   "my-app"
);

ExecutionResult result = client.executeWorkflow(req);
System.out.println("Run ID: " + result.runId);

// 3. Poll for status
RunIdInput statusReq = new RunIdInput();
statusReq.runId = result.runId;

ExecutionStatus status = client.getExecutionStatus(statusReq);
System.out.println("Status: " + status.status);
```

---

## Step 5 — Run the example

This repo contains a complete runnable example with polling.

```bash
# Clone this repo
git clone https://github.com/vextura/vex-user-guide.git
cd vex-user-guide/101-execute-workflow-java

# Set environment variables
export UWF_ENDPOINT=$(vexctl rip resolve uwf-engine <your-region>)
export VEX_TOKEN=$(vexctl auth token)
export WORKFLOW_ID=kaspi-payment-v1   # or any workflow registered in your cluster

# Build and run
mvn compile exec:java
```

Expected output:

```
Engine status : healthy
Workflows     : 3 registered
  • kaspi-payment-v1 — Kaspi Payment Fraud Detection
  • kaspi-payment-flow — Kaspi Payment Approval Flow
  • truckpay-settlement — TruckPay Settlement Processing

Executing workflow: kaspi-payment-v1
Run ID        : run-1780371635657067673
Initial status: pending
  [ 1s] status=running step=fraud-check
  [ 2s] status=running step=score-eval
  [ 3s] status=completed step=null

Final status  : completed
```

---

## SDK reference

| Method | Description |
|--------|-------------|
| `client.healthCheck()` | Check engine status (nats + redis connectivity) |
| `client.listWorkflows()` | List all registered workflow definitions |
| `client.getWorkflow(req)` | Get a single workflow definition by ID |
| `client.executeWorkflow(req)` | Execute a workflow synchronously, returns runId |
| `client.asyncExecuteWorkflow(req)` | Execute without waiting for completion |
| `client.getExecutionStatus(req)` | Poll execution status by runId |
| `client.listExecutions(req)` | List past executions (supports pagination) |
| `client.cancelExecution(req)` | Cancel a running execution |

---

## Using RIP-based endpoint resolution (production pattern)

For production code, pass the vex-config URL to let the SDK resolve the endpoint
automatically via RIP — no need to pass a fixed endpoint:

```java
// Resolves uwf-engine endpoint automatically at request time
// ripUrl comes from: vexctl rip resolve vex-config <region>
UwfEngineClient client = new UwfEngineClient(
    System.getenv("VEX_CONFIG_URL"),
    new BearerAuth(System.getenv("VEX_TOKEN"))
);
```

This way if the engine moves to a different node, the client picks it up without
a redeploy.

---

## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `UWF_ENDPOINT is required` | Env var not set | Run `export UWF_ENDPOINT=$(vexctl rip resolve uwf-engine <region>)` |
| `401 Unauthorized` | Token expired | Run `export VEX_TOKEN=$(vexctl auth token)` |
| `404 workflow not found` | Wrong workflow ID | Run `client.listWorkflows()` to see registered IDs |
| `Connection refused` | Engine not reachable | Check `vexctl rip list` — verify uwf-engine is registered |

---

## Next guides

- **102** — Execute a Workflow — Python SDK *(coming soon)*
- **103** — Execute a Workflow — Go SDK *(coming soon)*
- **104** — Deploy a New Service *(coming soon)*
- **105** — Set Up a CI/CD Pipeline *(coming soon)*
