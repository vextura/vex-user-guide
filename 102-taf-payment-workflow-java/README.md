# TAF Payment Workflow — Java Integration Guide

Integrate your application with the **`taf-payment-v1`** anti-fraud workflow using the [Vextura UWF Engine Java SDK](https://central.sonatype.com/artifact/ai.vextura/uwf-engine-sdk-java).

## How it works

```
Your application
    │
    │  POST /api/v1/workflows/taf-payment-v1/execute
    ▼
Vextura Workflow Engine  (no auth required)
    │
    │  invokes fn: taf-proxy, operation: SubmitTransaction
    ▼
TAF Anti-Fraud Service
    │
    └─ verdict returned to workflow → caller
```

## Maven dependency

```xml
<dependency>
    <groupId>ai.vextura</groupId>
    <artifactId>uwf-engine-sdk-java</artifactId>
    <version>1.2.6</version>
</dependency>
```

## Configuration

Add to `application.yml`:

```yaml
uwf:
  engine:
    url: http://172.30.75.85:8080
```

No authentication credentials are required. The engine accepts requests without an Authorization header.

## Adapter class

```java
package your.package.adapter.uwf;

import ai.vextura.uwf_engine.UwfEngineClient;
import ai.vextura.uwf_engine.models.ExecuteWorkflowInput;
import ai.vextura.uwf_engine.models.ExecutionResult;
import ai.vextura.uwf_engine.models.ExecutionStatus;
import ai.vextura.uwf_engine.models.RunIdInput;
import ai.vextura.uwf_engine.runtime.NoAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class UwfAntifraudAdapter {

    private static final Logger log = LoggerFactory.getLogger(UwfAntifraudAdapter.class);
    private static final String WORKFLOW_ID = "taf-payment-v1";
    private static final long POLL_TIMEOUT_MS  = 30_000;
    private static final long POLL_INTERVAL_MS = 1_000;

    private final UwfEngineClient client;

    public UwfAntifraudAdapter(@Value("${uwf.engine.url}") String engineUrl) {
        this.client = UwfEngineClient.withEndpoint(engineUrl, NoAuth.INSTANCE);
    }

    public AntifraudResult check(String orderId, long amount, String currency,
                                  String channel, String cardNumber, String userId,
                                  String merchantId, String terminalId,
                                  String sicCode, String country, String city) {
        try {
            ExecuteWorkflowInput req = new ExecuteWorkflowInput();
            req.workflowId = WORKFLOW_ID;
            req.inputData  = buildPayload(orderId, amount, currency, channel,
                                          cardNumber, userId, merchantId,
                                          terminalId, sicCode, country, city);

            ExecutionResult submitted = client.executeWorkflow(req);
            String runId = submitted.runId;
            log.info("Antifraud submitted orderId={} runId={}", orderId, runId);

            ExecutionStatus status = pollUntilDone(runId);

            if ("completed".equals(status.status)) {
                RunIdInput resultReq = new RunIdInput();
                resultReq.runId = runId;
                ExecutionResult result = client.getExecutionResult(resultReq);

                @SuppressWarnings("unchecked")
                Map<String, Object> verdict = result.result != null
                    ? (Map<String, Object>) result.result.get("result")
                    : null;

                boolean fraud     = Boolean.TRUE.equals(verdict != null ? verdict.get("af_fraud")     : null);
                boolean blocked   = Boolean.TRUE.equals(verdict != null ? verdict.get("af_blocked")   : null);
                boolean validated = Boolean.TRUE.equals(verdict != null ? verdict.get("af_validated") : null);

                log.info("Antifraud result orderId={} runId={} fraud={} blocked={} validated={}",
                         orderId, runId, fraud, blocked, validated);
                return new AntifraudResult(runId, fraud, blocked, validated, null);
            } else {
                log.error("Antifraud workflow failed orderId={} runId={} status={}", orderId, runId, status.status);
                return AntifraudResult.failed(runId, "workflow status: " + status.status);
            }

        } catch (Exception e) {
            log.error("Antifraud SDK error orderId={}", orderId, e);
            return AntifraudResult.failed(null, e.getMessage());
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
        throw new RuntimeException("Antifraud timed out runId=" + runId);
    }

    private Map<String, Object> buildPayload(String orderId, long amount, String currency,
                                              String channel, String cardNumber, String userId,
                                              String merchantId, String terminalId,
                                              String sicCode, String country, String city) {
        Map<String, Object> p = new HashMap<>();
        p.put("id",                 orderId);
        p.put("transactionType",    "purchase");
        p.put("amount",             amount);
        p.put("currency",           currency);
        p.put("channel",            channel);
        p.put("date",               Instant.now().toString());
        p.put("sourceCardNumber",   cardNumber);
        p.put("sourceUserId",       userId);
        p.put("merchantId",         merchantId);
        p.put("merchantTerminalId", terminalId);
        p.put("sicCode",            sicCode);
        p.put("transactionCountry", country);
        p.put("transactionCity",    city);
        return p;
    }

    public static class AntifraudResult {
        public final String  runId;
        public final boolean fraud;
        public final boolean blocked;
        public final boolean validated;
        public final String  error;

        public AntifraudResult(String runId, boolean fraud, boolean blocked,
                                boolean validated, String error) {
            this.runId      = runId;
            this.fraud      = fraud;
            this.blocked    = blocked;
            this.validated  = validated;
            this.error      = error;
        }

        public boolean isSuccess() { return error == null; }

        public static AntifraudResult failed(String runId, String error) {
            return new AntifraudResult(runId, false, false, false, error);
        }
    }
}
```

## Transaction payload fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique transaction / order ID |
| `transactionType` | string | `purchase`, `refund`, `withdrawal`, etc. |
| `amount` | number | Amount in minor units (e.g. tiyn for KZT) |
| `currency` | string | ISO 4217 code (`KZT`, `USD`, …) |
| `channel` | string | `pos`, `atm`, `online`, `mobile` |
| `date` | string | ISO 8601 timestamp |
| `sourceCardNumber` | string | Masked PAN (`440000******1234`) |
| `sourceUserId` | string | Cardholder identifier |
| `merchantId` | string | Merchant identifier |
| `merchantTerminalId` | string | Terminal identifier |
| `sicCode` | string | ISO 18245 MCC code |
| `transactionCountry` | string | ISO 3166-1 alpha-2 country |
| `transactionCity` | string | City name |

## Verdict fields

| Field | Type | Meaning |
|---|---|---|
| `af_fraud` | boolean | Transaction flagged as fraud |
| `af_blocked` | boolean | Transaction blocked by TAF |
| `af_validated` | boolean | Transaction passed validation |
