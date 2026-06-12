package ai.vextura.taf.example;

import ai.vextura.uwf_engine.UwfEngineClient;
import ai.vextura.uwf_engine.models.*;
import ai.vextura.uwf_engine.runtime.BearerAuth;
import ai.vextura.uwf_engine.runtime.M2MAuth;
import ai.vextura.uwf_engine.runtime.AuthProvider;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Guide 102: TAF Payment Workflow — Java SDK
 *
 * Demonstrates synchronous and asynchronous execution of the taf-payment-v1
 * anti-fraud workflow using the Vextura UWF Engine SDK.
 *
 * Required environment variables:
 *   VEX_GATE_URL      — Vextura API gateway URL (from your admin)
 *   VEX_CLIENT_ID     — M2M client ID (recommended)
 *   VEX_CLIENT_SECRET — M2M client secret (recommended)
 *   VEX_TOKEN         — static Bearer token (alternative for local dev)
 *
 * Run:
 *   mvn compile exec:java
 */
public class TafPaymentExample {

    static final String WORKFLOW_ID = "taf-payment-v1";

    public static void main(String[] args) throws Exception {
        String gateUrl = requireEnv("VEX_GATE_URL");
        AuthProvider auth = resolveAuth(gateUrl);

        UwfEngineClient client = UwfEngineClient.withEndpoint(gateUrl, auth);

        // Verify connectivity before sending transactions
        HealthResponse health = client.healthCheck();
        System.out.println("Engine status : " + health.status);
        System.out.println("NATS          : " + (health.nats != null ? health.nats.ok : "n/a"));
        System.out.println("Redis         : " + (health.redis != null ? health.redis.ok : "n/a"));
        System.out.println();

        runSyncExample(client);
        System.out.println();
        runAsyncExample(client);
    }

    // -----------------------------------------------------------------------
    // Synchronous — blocks until the workflow completes or times out
    // -----------------------------------------------------------------------
    static void runSyncExample(UwfEngineClient client) {
        System.out.println("--- Synchronous execution ---");

        ExecuteWorkflowInput req = new ExecuteWorkflowInput();
        req.workflowId = WORKFLOW_ID;
        req.inputData  = buildTransaction("TXN-" + shortId());
        req.timeoutMs  = 20_000;  // 20 s — TAF response is usually < 2 s

        ExecutionResult result = client.executeWorkflow(req);

        System.out.println("run_id   : " + result.runId);
        System.out.println("status   : " + result.status);
        System.out.println("duration : " + result.durationMs + " ms");
        if (result.output != null && !result.output.isEmpty()) {
            System.out.println("output   : " + result.output);
        }
        if (result.error != null && !result.error.isBlank()) {
            System.out.println("error    : " + result.error);
        }
    }

    // -----------------------------------------------------------------------
    // Asynchronous — submit immediately, poll for status
    // -----------------------------------------------------------------------
    static void runAsyncExample(UwfEngineClient client) throws InterruptedException {
        System.out.println("--- Asynchronous execution + polling ---");

        AsyncExecuteInput req = new AsyncExecuteInput();
        req.workflowId = WORKFLOW_ID;
        req.inputData  = buildTransaction("TXN-" + shortId());

        AsyncExecuteResponse submitted = client.asyncExecuteWorkflow(req);
        System.out.println("submitted run_id : " + submitted.runId);

        ExecutionStatus status = pollUntilDone(client, submitted.runId, 30_000);
        System.out.println("final status     : " + status.status);

        if ("completed".equals(status.status)) {
            RunIdInput resultReq = new RunIdInput();
            resultReq.runId = submitted.runId;
            ExecutionResult result = client.getExecutionResult(resultReq);
            System.out.println("output           : " + result.output);
        }
    }

    // -----------------------------------------------------------------------
    // Transaction payload — fields consumed by taf-proxy SubmitTransaction
    // -----------------------------------------------------------------------
    static Map<String, Object> buildTransaction(String txnId) {
        Map<String, Object> p = new HashMap<>();
        p.put("id",                 txnId);
        p.put("transactionType",    "purchase");     // purchase | refund | withdrawal | transfer
        p.put("amount",             5000);           // minor units (tiyn for KZT)
        p.put("currency",           "KZT");
        p.put("channel",            "pos");          // pos | atm | online | mobile
        p.put("date",               Instant.now().toString());
        p.put("sourceCardNumber",   "440000******1234");
        p.put("sourceUserId",       "user-001");
        p.put("merchantId",         "MERCH-0042");
        p.put("merchantTerminalId", "TERM-0001");
        p.put("sicCode",            "5411");         // ISO 18245 MCC
        p.put("transactionCountry", "KZ");
        p.put("transactionCity",    "Astana");
        return p;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    static ExecutionStatus pollUntilDone(UwfEngineClient client, String runId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        RunIdInput req = new RunIdInput();
        req.runId = runId;

        while (System.currentTimeMillis() < deadline) {
            ExecutionStatus s = client.getExecutionStatus(req);
            System.out.println("  polling... status=" + s.status
                    + (s.currentStep != null ? " step=" + s.currentStep : ""));
            if ("completed".equals(s.status) || "failed".equals(s.status)) {
                return s;
            }
            Thread.sleep(1_000);
        }
        throw new RuntimeException("timed out waiting for run_id=" + runId);
    }

    static AuthProvider resolveAuth(String gateUrl) {
        String clientId     = System.getenv("VEX_CLIENT_ID");
        String clientSecret = System.getenv("VEX_CLIENT_SECRET");
        String staticToken  = System.getenv("VEX_TOKEN");

        if (clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()) {
            System.out.println("[auth] M2M client_credentials (" + clientId + ")");
            return new M2MAuth(gateUrl, clientId, clientSecret);
        }
        if (staticToken != null && !staticToken.isBlank()) {
            System.out.println("[auth] static Bearer token");
            return new BearerAuth(staticToken);
        }
        System.err.println("ERROR: set VEX_CLIENT_ID+VEX_CLIENT_SECRET or VEX_TOKEN");
        System.exit(1);
        return null;
    }

    static String requireEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            System.err.println("ERROR: " + key + " is required");
            System.exit(1);
        }
        return v;
    }

    static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
