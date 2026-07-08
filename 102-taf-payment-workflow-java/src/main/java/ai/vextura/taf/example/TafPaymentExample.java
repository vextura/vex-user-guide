package ai.vextura.taf.example;

import ai.vextura.uwf_engine.UwfEngineClient;
import ai.vextura.uwf_engine.models.*;
import ai.vextura.uwf_engine.runtime.AuthProvider;
import ai.vextura.uwf_engine.runtime.BearerAuth;
import ai.vextura.uwf_engine.runtime.M2MAuth;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Submits a payment transaction to the TAF anti-fraud workflow on Qazpost and
 * prints the resulting verdict. Verified against the qzp-kz-north-1 cluster on
 * 2026-07-08.
 *
 * The workflow ID is {@code taf-payment-v1}. It runs three steps:
 *
 *   evaluate  → predicate-eval.EvaluatePredicates
 *   route     → choice on verdict (block/review → create-incident, allow → end)
 *   create-incident → incident-trigger.TriggerFromVerdict (skip on failure)
 *
 * Environment variables (required — the workflow gate always demands a JWT):
 *
 *   VEX_GATE_URL       base URL of the workflow gate (e.g. http://172.30.75.94:8080/workflow)
 *   VEX_AUTH_URL       base URL for token endpoint     (e.g. http://172.30.75.94:8080)
 *   VEX_CLIENT_ID      client_credentials client id   (e.g. "admin")
 *   VEX_CLIENT_SECRET  client_credentials client secret (Qazpost admin password)
 *
 * Alternate: skip client-credentials and pre-issue a token, then set
 * VEX_TOKEN and omit VEX_CLIENT_ID/SECRET.
 *
 * Run:
 *   mvn compile exec:java
 */
public class TafPaymentExample {

    static final String WORKFLOW_ID = "taf-payment-v1";
    // Registered format on Qazpost (binance-pay) — active ruleset attached.
    static final String FORMAT_ID = "fmt_01kwc389zs8rapcqz3mf7yantx";

    public static void main(String[] args) throws Exception {
        String gateUrl = requireEnv("VEX_GATE_URL");
        AuthProvider auth = resolveAuth(gateUrl);

        UwfEngineClient client = UwfEngineClient.withEndpoint(gateUrl, auth);

        System.out.println("=== TAF Payment Workflow — Qazpost ===");
        System.out.println("Gate      : " + gateUrl);
        System.out.println("Workflow  : " + WORKFLOW_ID);
        System.out.println("Format ID : " + FORMAT_ID);
        System.out.println();

        runCase(client, "safe-small", "charge", "880101300123", 50_000L, "CTR-1");
        System.out.println();
        runCase(client, "review-hi",  "charge", "880101300123", 2_000_000L, "CTR-2");
        System.out.println();
        runCase(client, "block-payout-nocontract", "payout", "880101300123", 500_000L, "");
    }

    static void runCase(UwfEngineClient client, String label, String orderType,
                        String clientId, long amount, String contractNumber)
            throws InterruptedException {
        String requestId = "example-" + label + "-" + shortId();
        System.out.println("--- " + label + " (request_id=" + requestId + ") ---");

        ExecuteWorkflowInput req = new ExecuteWorkflowInput();
        req.workflowId = WORKFLOW_ID;
        req.inputData  = buildPayload(requestId, orderType, clientId, amount, contractNumber);

        ExecutionResult ack = client.executeWorkflow(req);
        System.out.println("run_id    : " + ack.runId);

        ExecutionStatus status = pollUntilDone(client, ack.runId);
        System.out.println("status    : " + status.status);
        if (status.currentStep != null && !status.currentStep.isEmpty()) {
            System.out.println("last step : " + status.currentStep);
        }
        if (status.error != null && !status.error.isEmpty()) {
            System.out.println("error     : " + status.error);
        }

        if (!"completed".equals(status.status)) {
            return;
        }

        RunIdInput resultReq = new RunIdInput();
        resultReq.runId = ack.runId;
        ExecutionResult result = client.getExecutionResult(resultReq);
        Map<String, Object> data = result.result != null ? result.result : Map.of();
        System.out.println("verdict   : " + data.getOrDefault("verdict", "unknown"));
        System.out.println("ruleset_id: " + data.getOrDefault("ruleset_id", "?"));
        System.out.println("per_rule  : " + data.getOrDefault("per_rule", "[]"));
    }

    // taf-payment-v1 expects a wrapped input: { input_data: { request_id, format_id, tx: {...} } }
    // UwfEngineClient wraps whatever we pass to inputData into `input_data` on the wire.
    static Map<String, Object> buildPayload(String requestId, String orderType,
                                             String clientId, long amount,
                                             String contractNumber) {
        Map<String, Object> tx = new HashMap<>();
        tx.put("order_id",        "ORD-" + shortId());
        tx.put("order_type",      orderType);
        tx.put("client_id",       clientId);
        tx.put("amount",          amount);
        tx.put("contract_number", contractNumber == null ? "" : contractNumber);

        Map<String, Object> payload = new HashMap<>();
        payload.put("request_id", requestId);
        payload.put("format_id",  FORMAT_ID);
        payload.put("tx",         tx);
        return payload;
    }

    static ExecutionStatus pollUntilDone(UwfEngineClient client, String runId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        RunIdInput req = new RunIdInput();
        req.runId = runId;
        while (System.currentTimeMillis() < deadline) {
            ExecutionStatus s = client.getExecutionStatus(req);
            if ("completed".equals(s.status) || "failed".equals(s.status)
                || "cancelled".equals(s.status)) {
                return s;
            }
            Thread.sleep(500);
        }
        throw new RuntimeException("timed out waiting for run_id=" + runId);
    }

    static AuthProvider resolveAuth(String gateUrl) {
        String authUrl      = System.getenv("VEX_AUTH_URL");
        String clientId     = System.getenv("VEX_CLIENT_ID");
        String clientSecret = System.getenv("VEX_CLIENT_SECRET");
        String staticToken  = System.getenv("VEX_TOKEN");

        if (clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()) {
            String tokenBase = authUrl != null && !authUrl.isBlank()
                ? authUrl
                : stripPathSuffix(gateUrl);
            System.out.println("[auth] M2M client_credentials (" + clientId + " @ " + tokenBase + ")");
            return new M2MAuth(tokenBase, clientId, clientSecret);
        }
        if (staticToken != null && !staticToken.isBlank()) {
            System.out.println("[auth] static Bearer token from VEX_TOKEN");
            return new BearerAuth(staticToken);
        }
        System.err.println("ERROR: set VEX_CLIENT_ID+VEX_CLIENT_SECRET (M2M) or VEX_TOKEN (static)");
        System.exit(1);
        return null; // unreachable
    }

    static String stripPathSuffix(String url) {
        int hostEnd = url.indexOf('/', url.indexOf("://") + 3);
        return hostEnd < 0 ? url : url.substring(0, hostEnd);
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
