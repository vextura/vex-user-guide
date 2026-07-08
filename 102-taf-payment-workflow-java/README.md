# TAF Payment Workflow — Java Integration Guide

Submit a payment transaction to the `taf-payment-v1` anti-fraud workflow on Qazpost and get a fraud verdict back.

Verified against the Qazpost cluster (qzp-kz-north-1) on 2026-07-08 using the freshly-generated SDK.

## How it works

```
Your application
    │  POST /workflow/executions/taf-payment-v1
    ▼
vex-gate (JWT-protected)
    │  proxies to unified-workflow-go
    ▼
Workflow: evaluate → route → create-incident
    │        │           │           │
    │   predicate-eval   choice    incident-trigger
    │   fn               (verdict)  fn (block / review only)
    ▼
{ verdict: "allow" | "review" | "block", per_rule: [...] }
```

Steps:

1. `evaluate` — runs `predicate-eval.EvaluatePredicates` against the requested `format_id`. Returns `verdict`, `per_rule`, `ruleset_id`, `ruleset_version`, `eval_ms`.
2. `route` — a workflow-level choice. `verdict=block` or `verdict=review` → `create-incident`. `verdict=allow` → workflow terminates cleanly.
3. `create-incident` — publishes `incident.<tenant>.created` to JetStream; `vex-incident-mgr-ingest` consumes it and creates the incident row visible in the incident-mgr UI. `failure_policy=skip` so a publish failure does not fail the workflow.

## Endpoint + auth

**Qazpost gate**: `http://172.30.75.94:8080` (reachable directly from the Qazpost bastion `10.200.1.2`, or through an SSH tunnel from your workstation).

**Authentication is required.** Requests without a valid Bearer JWT get `401`. Two ways to get a token:

1. **M2M `client_credentials`** (recommended for services) — one-shot POST to `/auth/token`:

    ```bash
    curl -X POST http://172.30.75.94:8080/auth/token \
      --data-urlencode grant_type=client_credentials \
      --data-urlencode client_id=admin \
      --data-urlencode client_secret=<qzp-admin-password> \
      | jq -r .access_token
    ```

    Response: `{"access_token":"eyJ…","expires_in":7200,"token_type":"Bearer"}`.

2. **Static token** (dev / demos) — export any pre-issued JWT via `VEX_TOKEN`.

The Java SDK's `M2MAuth` handles refresh automatically; use `BearerAuth` for static.

## Maven dependency

```xml
<dependency>
    <groupId>ai.vextura</groupId>
    <artifactId>uwf-engine-sdk-java</artifactId>
    <version>1.2.4</version>
</dependency>
```

> The SDK is regenerated from `smithy/vex_uwf.smithy` via `vexctl sdk generate --lang java`. Pin to a version compatible with the current workflow-engine deployment on Qazpost.

## Configuration

`application.yml`:

```yaml
vex:
  gate:
    url: http://172.30.75.94:8080/workflow
  auth:
    url: http://172.30.75.94:8080
    client-id: admin
    client-secret: <qzp-admin-password>
```

The engine SDK expects `gate.url` to include the `/workflow` prefix — its generated methods target the smithy-declared paths (`/executions/{id}`, `/executions`) and the gate strips `/workflow` before proxying.

## Request payload

`taf-payment-v1` accepts a **wrapped** input:

```json
{
  "input_data": {
    "request_id": "<unique correlation id>",
    "format_id":  "fmt_01kwc389zs8rapcqz3mf7yantx",
    "tx": {
      "order_id":        "ORD-001",
      "order_type":      "charge",
      "client_id":       "880101300123",
      "amount":          100000,
      "contract_number": "CTR-A"
    }
  }
}
```

Field notes:

| Field | Type | Notes |
|---|---|---|
| `request_id` | string | Your correlation id — surfaces in the verdict + track endpoint |
| `format_id` | string | Registered format on Qazpost — use `fmt_01kwc389zs8rapcqz3mf7yantx` (binance-pay) |
| `tx.order_id` | string | Merchant-side order reference |
| `tx.order_type` | enum | `charge` \| `payout` |
| `tx.client_id` | string | Client identifier used by ruleset predicates |
| `tx.amount` | integer | Amount in minor units |
| `tx.contract_number` | string | Contract reference (may be empty for anonymous flows — see rules) |

## Verdict shape

`predicate-eval` returns:

```json
{
  "request_id":       "<echoed>",
  "verdict":          "allow" | "review" | "block",
  "per_rule": [
    { "rule_id": "r_…", "matched": false, "eval_us": 3 },
    { "rule_id": "r_…", "matched": true,  "action": "review", "eval_us": 10 }
  ],
  "ruleset_id":       "rs_01kwc39kpyjpbphsk16bswj32g",
  "ruleset_version":  2,
  "eval_ms":          16.061
}
```

When `verdict=review` or `verdict=block` the workflow proceeds to `create-incident` and you can look up the resulting incident in the incident-mgr UI. When `verdict=allow` the workflow terminates cleanly.

## Adapter class

```java
package your.package.adapter.uwf;

import ai.vextura.uwf_engine.UwfEngineClient;
import ai.vextura.uwf_engine.models.*;
import ai.vextura.uwf_engine.runtime.M2MAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class TafPaymentAdapter {

    private static final Logger log = LoggerFactory.getLogger(TafPaymentAdapter.class);
    private static final String WORKFLOW_ID = "taf-payment-v1";
    private static final String FORMAT_ID   = "fmt_01kwc389zs8rapcqz3mf7yantx"; // binance-pay
    private static final long POLL_TIMEOUT_MS  = 15_000;
    private static final long POLL_INTERVAL_MS = 500;

    private final UwfEngineClient client;

    public TafPaymentAdapter(
        @Value("${vex.gate.url}")       String gateUrl,
        @Value("${vex.auth.url}")       String authUrl,
        @Value("${vex.auth.client-id}") String clientId,
        @Value("${vex.auth.client-secret}") String clientSecret
    ) {
        this.client = UwfEngineClient.withEndpoint(
            gateUrl,
            new M2MAuth(authUrl, clientId, clientSecret)
        );
    }

    public TafResult check(String orderId, String orderType, String clientId,
                            long amount, String contractNumber) {
        String requestId = "sdk-" + UUID.randomUUID();

        Map<String, Object> tx = new HashMap<>();
        tx.put("order_id",        orderId);
        tx.put("order_type",      orderType);
        tx.put("client_id",       clientId);
        tx.put("amount",          amount);
        tx.put("contract_number", contractNumber == null ? "" : contractNumber);

        Map<String, Object> payload = new HashMap<>();
        payload.put("request_id", requestId);
        payload.put("format_id",  FORMAT_ID);
        payload.put("tx",         tx);

        try {
            ExecuteWorkflowInput req = new ExecuteWorkflowInput();
            req.workflowId = WORKFLOW_ID;
            req.inputData  = payload;

            ExecutionResult submitted = client.executeWorkflow(req);
            String runId = submitted.runId;
            log.info("taf submitted orderId={} request_id={} run_id={}",
                orderId, requestId, runId);

            ExecutionStatus status = pollUntilDone(runId);

            if ("completed".equals(status.status)) {
                RunIdInput resultReq = new RunIdInput();
                resultReq.runId = runId;
                ExecutionResult result = client.getExecutionResult(resultReq);

                Map<String, Object> data = result.result != null ? result.result : Map.of();
                String verdict = (String) data.getOrDefault("verdict", "unknown");
                Object perRule = data.get("per_rule");
                log.info("taf verdict orderId={} request_id={} verdict={} per_rule={}",
                    orderId, requestId, verdict, perRule);
                return new TafResult(runId, requestId, verdict, perRule, null);
            }
            log.warn("taf workflow non-terminal orderId={} runId={} status={}",
                orderId, runId, status.status);
            return TafResult.failed(runId, requestId,
                "workflow status: " + status.status);

        } catch (Exception e) {
            log.error("taf adapter error orderId={}", orderId, e);
            return TafResult.failed(null, requestId, e.getMessage());
        }
    }

    private ExecutionStatus pollUntilDone(String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        RunIdInput req = new RunIdInput();
        req.runId = runId;
        while (System.currentTimeMillis() < deadline) {
            ExecutionStatus s = client.getExecutionStatus(req);
            if ("completed".equals(s.status) || "failed".equals(s.status)) {
                return s;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new RuntimeException("TAF timed out runId=" + runId);
    }

    public static class TafResult {
        public final String  runId;
        public final String  requestId;
        public final String  verdict;    // "allow" | "review" | "block"
        public final Object  perRule;
        public final String  error;

        public TafResult(String runId, String requestId, String verdict,
                          Object perRule, String error) {
            this.runId     = runId;
            this.requestId = requestId;
            this.verdict   = verdict;
            this.perRule   = perRule;
            this.error     = error;
        }

        public boolean isBlocked() { return "block".equals(verdict); }
        public boolean isReview()  { return "review".equals(verdict); }
        public boolean isAllow()   { return "allow".equals(verdict); }

        public static TafResult failed(String runId, String requestId, String error) {
            return new TafResult(runId, requestId, "error", null, error);
        }
    }
}
```

## Curl reference (matches the SDK wire calls exactly)

```bash
export GATE=http://172.30.75.94:8080
export TOK=$(curl -s -X POST $GATE/auth/token \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode client_id=admin \
  --data-urlencode client_secret=<qzp-admin-password> \
  | jq -r .access_token)

# safe transaction — expect verdict=allow
curl -s -X POST $GATE/workflow/executions/taf-payment-v1 \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" \
  -H "X-Vex-Tenant: vextura" \
  -d @- <<EOF | jq
{
  "input_data": {
    "request_id": "curl-safe-$(date +%s)",
    "format_id":  "fmt_01kwc389zs8rapcqz3mf7yantx",
    "tx": {
      "order_id":        "ORD-001",
      "order_type":      "charge",
      "client_id":       "880101300123",
      "amount":          100000,
      "contract_number": "CTR-A"
    }
  }
}
EOF
```

Response:

```json
{ "message": "Workflow queued for execution",
  "run_id":  "run-1783487324036644893",
  "status":  "pending" }
```

Poll for the verdict:

```bash
RID="curl-safe-<same as above>"
curl -s -H "Authorization: Bearer $TOK" \
       -H "X-Vex-Tenant: vextura" \
       "$GATE/antifraud/track/$RID" | jq

# → { "request_id": "curl-safe-…", "status": "completed",
#     "verdict":    "allow", "verdict_id": "v_…" }
```

Or poll the workflow execution directly:

```bash
curl -s -H "Authorization: Bearer $TOK" \
       "$GATE/workflow/executions/run-1783487324036644893" | jq
```

## Testing tips

- The transaction shape drives the verdict — the same `client_id` + a suspicious `order_type=payout` + empty `contract_number` will typically return `verdict=block` (matches a rule), whereas a normal `charge` with a contract number returns `allow`.
- The verdict is deterministic per (`format_id`, ruleset_version, `tx`); replay the same `request_id` to get the same verdict from cache.
- Block / review verdicts create an incident visible in the incident-mgr UI (`http://172.30.75.93:3006/` via bastion tunnel).
