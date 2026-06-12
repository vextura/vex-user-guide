package ai.vextura.taf.example;

import ai.vextura.uwf_engine.UwfEngineClient;
import ai.vextura.uwf_engine.models.*;
import ai.vextura.uwf_engine.runtime.BearerAuth;
import ai.vextura.uwf_engine.runtime.M2MAuth;
import ai.vextura.uwf_engine.runtime.NoAuth;
import ai.vextura.uwf_engine.runtime.AuthProvider;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Demonstrates submitting a payment transaction to the TAF anti-fraud workflow
 * using the Vextura UWF Engine SDK.
 *
 * Environment variables:
 *   VEX_GATE_URL      — base URL of the workflow engine (required)
 *   VEX_AUTH_URL      — base URL of the auth service for M2M (optional; defaults to VEX_GATE_URL)
 *   VEX_CLIENT_ID     — M2M client ID (optional)
 *   VEX_CLIENT_SECRET — M2M client secret (optional, paired with VEX_CLIENT_ID)
 *   VEX_TOKEN         — static Bearer token (optional fallback)
 *
 * If none of the auth variables are set the client sends requests with no
 * Authorization header — suitable for engine endpoints that require no auth.
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

        System.out.println("=== TAF Payment Workflow Example ===");
        System.out.println("Gate  : " + gateUrl);
        System.out.println("Workflow: " + WORKFLOW_ID);
        System.out.println();

        runSyncExample(client);
        System.out.println();
        runAsyncExample(client);
    }

    // --- Submit + poll (recommended) ------------------------------------------

    static void runSyncExample(UwfEngineClient client) throws InterruptedException {
        System.out.println("--- Submit + poll ---");

        ExecuteWorkflowInput req = new ExecuteWorkflowInput();
        req.workflowId = WORKFLOW_ID;
        req.inputData  = buildTransactionPayload("TXN-" + shortId());

        ExecutionResult submitted = client.executeWorkflow(req);
        System.out.println("run_id    : " + submitted.runId);

        ExecutionStatus status = pollUntilDone(client, submitted.runId, 30_000);
        System.out.println("status    : " + status.status);

        if ("completed".equals(status.status)) {
            RunIdInput resultReq = new RunIdInput();
            resultReq.runId = submitted.runId;
            ExecutionResult result = client.getExecutionResult(resultReq);
            @SuppressWarnings("unchecked")
            Map<String, Object> tafVerdict = result.result != null
                ? (Map<String, Object>) result.result.get("result")
                : null;
            System.out.println("af_fraud    : " + (tafVerdict != null ? tafVerdict.get("af_fraud") : "n/a"));
            System.out.println("af_blocked  : " + (tafVerdict != null ? tafVerdict.get("af_blocked") : "n/a"));
            System.out.println("af_validated: " + (tafVerdict != null ? tafVerdict.get("af_validated") : "n/a"));
        } else if (status.status != null && status.status.startsWith("fail")) {
            System.out.println("error     : " + status.currentStep);
        }
    }

    // --- Asynchronous ----------------------------------------------------------

    static void runAsyncExample(UwfEngineClient client) throws InterruptedException {
        System.out.println("--- Async execution + polling ---");

        ExecuteWorkflowInput req = new ExecuteWorkflowInput();
        req.workflowId = WORKFLOW_ID;
        req.inputData  = buildTransactionPayload("TXN-" + shortId());

        ExecutionResult submitted = client.executeWorkflow(req);
        System.out.println("run_id submitted: " + submitted.runId);

        String runId = submitted.runId;
        ExecutionStatus status = pollUntilDone(client, runId, 30_000);

        System.out.println("final status: " + status.status);
        if (status.currentStep != null) {
            System.out.println("last step   : " + status.currentStep);
        }

        if ("completed".equals(status.status)) {
            RunIdInput resultReq = new RunIdInput();
            resultReq.runId = runId;
            ExecutionResult result = client.getExecutionResult(resultReq);
            // Engine wraps TAF output in result.result — navigate one level in
            @SuppressWarnings("unchecked")
            Map<String, Object> tafVerdict = result.result != null
                ? (Map<String, Object>) result.result.get("result")
                : null;
            System.out.println("af_fraud    : " + (tafVerdict != null ? tafVerdict.get("af_fraud") : "n/a"));
            System.out.println("af_blocked  : " + (tafVerdict != null ? tafVerdict.get("af_blocked") : "n/a"));
            System.out.println("af_validated: " + (tafVerdict != null ? tafVerdict.get("af_validated") : "n/a"));
        }
    }

    // --- Helpers ---------------------------------------------------------------

    static Map<String, Object> buildTransactionPayload(String txnId) {
        Map<String, Object> p = new HashMap<>();
        p.put("id",                   txnId);
        p.put("transactionType",      "purchase");
        p.put("amount",               5000);          // in minor units (tiyn)
        p.put("currency",             "KZT");
        p.put("channel",              "pos");
        p.put("date",                 Instant.now().toString());
        p.put("sourceCardNumber",     "440000******1234");
        p.put("sourceUserId",         "user-001");
        p.put("merchantId",           "MERCH-0042");
        p.put("merchantTerminalId",   "TERM-0001");
        p.put("sicCode",              "5411");
        p.put("transactionCountry",   "KZ");
        p.put("transactionCity",      "Astana");
        return p;
    }

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
        String authUrl      = System.getenv("VEX_AUTH_URL");
        String clientId     = System.getenv("VEX_CLIENT_ID");
        String clientSecret = System.getenv("VEX_CLIENT_SECRET");
        String staticToken  = System.getenv("VEX_TOKEN");

        if (clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank()) {
            String tokenEndpoint = (authUrl != null && !authUrl.isBlank()) ? authUrl : gateUrl;
            System.out.println("[auth] M2M client_credentials (" + clientId + " @ " + tokenEndpoint + ")");
            return new M2MAuth(tokenEndpoint, clientId, clientSecret);
        }
        if (staticToken != null && !staticToken.isBlank()) {
            System.out.println("[auth] static Bearer token");
            return new BearerAuth(staticToken);
        }
        System.out.println("[auth] no auth (endpoint requires no Authorization header)");
        return NoAuth.INSTANCE;
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
