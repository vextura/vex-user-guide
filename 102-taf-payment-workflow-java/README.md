# 102 — TAF Payment Anti-Fraud Workflow — Java SDK

**SDK:** `ai.vextura:uwf-engine-sdk-java:1.2.4`
**Workflow:** `taf-payment-v1`

Submit a payment transaction to the TAF anti-fraud service and receive a verdict — synchronously or asynchronously.

---

## Prerequisites

| Tool  | Version |
|-------|---------|
| Java  | 21+     |
| Maven | 3.9+    |

---

## What your Vextura admin gives you

| Variable           | Description                        |
|--------------------|------------------------------------|
| `VEX_GATE_URL`     | Vextura API gateway URL            |
| `VEX_CLIENT_ID`    | Your M2M client ID                 |
| `VEX_CLIENT_SECRET`| Your M2M client secret             |

---

## How it works

```
Your application
    │
    │  POST /workflow/executions/taf-payment-v1   (Bearer JWT)
    ▼
vex-gate  (auth + routing)
    │
    │  fn: taf-proxy  operation: SubmitTransaction
    ▼
TAF Anti-Fraud Service
    │
    └─ verdict → workflow result → your application
```

The `taf-payment-v1` workflow is a single step — it delegates to the TAF service entirely. No business logic lives in the workflow layer.

---

## Transaction payload fields

| Field                | Type   | Description                                 |
|----------------------|--------|---------------------------------------------|
| `id`                 | string | Unique transaction ID                       |
| `transactionType`    | string | `purchase`, `refund`, `withdrawal`, `transfer` |
| `amount`             | number | Amount in minor units (e.g. tiyn for KZT)   |
| `currency`           | string | ISO 4217 (`KZT`, `USD`, …)                  |
| `channel`            | string | `pos`, `atm`, `online`, `mobile`            |
| `date`               | string | ISO 8601 timestamp                          |
| `sourceCardNumber`   | string | Masked PAN (`440000******1234`)             |
| `sourceUserId`       | string | Cardholder identifier                       |
| `merchantId`         | string | Merchant identifier                         |
| `merchantTerminalId` | string | Terminal identifier                         |
| `sicCode`            | string | ISO 18245 MCC code                          |
| `transactionCountry` | string | ISO 3166-1 alpha-2                          |
| `transactionCity`    | string | City name                                   |

---

## Step 1 — Add the dependency

```xml
<dependency>
    <groupId>ai.vextura</groupId>
    <artifactId>uwf-engine-sdk-java</artifactId>
    <version>1.2.4</version>
</dependency>
```

---

## Step 2 — Create the client

```java
import ai.vextura.uwf_engine.UwfEngineClient;
import ai.vextura.uwf_engine.runtime.M2MAuth;

String gateUrl = System.getenv("VEX_GATE_URL");

M2MAuth auth = new M2MAuth(
    gateUrl,
    System.getenv("VEX_CLIENT_ID"),
    System.getenv("VEX_CLIENT_SECRET")
);

UwfEngineClient client = UwfEngineClient.withEndpoint(gateUrl, auth);
```

---

## Step 3a — Execute synchronously

Blocks until the workflow completes (or `timeoutMs` elapses):

```java
import ai.vextura.uwf_engine.models.*;
import java.util.Map;

ExecuteWorkflowInput req = new ExecuteWorkflowInput();
req.workflowId = "taf-payment-v1";
req.inputData  = Map.of(
    "id",              "TXN-001",
    "transactionType", "purchase",
    "amount",          5000,
    "currency",        "KZT",
    "channel",         "pos",
    "date",            "2026-06-11T10:00:00Z",
    "sourceCardNumber","440000******1234",
    "sourceUserId",    "user-001",
    "merchantId",      "MERCH-0042",
    "merchantTerminalId", "TERM-0001",
    "sicCode",         "5411",
    "transactionCountry", "KZ",
    "transactionCity", "Astana"
);
req.timeoutMs = 20_000;

ExecutionResult result = client.executeWorkflow(req);
System.out.println("status : " + result.status);
System.out.println("output : " + result.output);
```

---

## Step 3b — Execute asynchronously (100 TPS pattern)

For high-throughput scenarios — submit and poll separately:

```java
AsyncExecuteInput req = new AsyncExecuteInput();
req.workflowId = "taf-payment-v1";
req.inputData  = txnPayload;  // same map as above

// Submit — returns immediately
AsyncExecuteResponse submitted = client.asyncExecuteWorkflow(req);
String runId = submitted.runId;

// Poll — check status until completed/failed
RunIdInput statusReq = new RunIdInput();
statusReq.runId = runId;

for (int i = 0; i < 30; i++) {
    ExecutionStatus s = client.getExecutionStatus(statusReq);
    if ("completed".equals(s.status) || "failed".equals(s.status)) break;
    Thread.sleep(1_000);
}

// Fetch result
RunIdInput resultReq = new RunIdInput();
resultReq.runId = runId;
ExecutionResult result = client.getExecutionResult(resultReq);
System.out.println("verdict: " + result.output);
```

---

## Step 4 — Run the example

```bash
git clone https://github.com/vextura/vex-user-guide.git
cd vex-user-guide/102-taf-payment-workflow-java

export VEX_GATE_URL=YOUR_GATE_URL
export VEX_CLIENT_ID=YOUR_CLIENT_ID
export VEX_CLIENT_SECRET=YOUR_CLIENT_SECRET

mvn compile exec:java
```

Expected output:
```
[auth] M2M client_credentials (YOUR_CLIENT_ID)
Engine status : healthy
NATS          : true
Redis         : true

--- Synchronous execution ---
run_id   : run-1781232613338083889
status   : completed
duration : 1623 ms
output   : {verdict=pass, score=12, ...}

--- Asynchronous execution + polling ---
submitted run_id : run-1781232613338083999
  polling... status=running step=fraud-check
  polling... status=completed step=fraud-check
final status     : completed
output           : {verdict=pass, score=12, ...}
```

---

## SDK reference

| Method | Description |
|--------|-------------|
| `client.healthCheck()` | Check engine + NATS + Redis status |
| `client.executeWorkflow(req)` | Execute synchronously, returns `ExecutionResult` |
| `client.asyncExecuteWorkflow(req)` | Submit without waiting, returns `runId` |
| `client.getExecutionStatus(req)` | Poll execution by `runId` |
| `client.getExecutionResult(req)` | Fetch final output once completed |

---

## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `VEX_GATE_URL is required` | Env var not set | Set all env vars from your admin |
| `token request failed (401)` | Wrong credentials | Re-confirm with your admin |
| `404 workflow not found` | Wrong workflow ID | Verify `taf-payment-v1` is deployed |
| `Connection refused` | Wrong URL or no network | Confirm cluster network access |
