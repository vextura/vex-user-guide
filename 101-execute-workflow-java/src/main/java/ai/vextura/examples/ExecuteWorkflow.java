package ai.vextura.examples;

import ai.vextura.uwf_engine.UwfEngineClient;
import ai.vextura.uwf_engine.models.*;
import ai.vextura.uwf_engine.runtime.AuthProvider;
import ai.vextura.uwf_engine.runtime.BearerAuth;
import ai.vextura.uwf_engine.runtime.M2MAuth;
import ai.vextura.uwf_engine.runtime.NoAuth;

import java.util.HashMap;
import java.util.Map;

/**
 * Execute a workflow and poll until completion.
 *
 * Environment variables:
 *   VEX_GATE_URL      — workflow engine URL (required)
 *   VEX_AUTH_URL      — auth service URL for M2M (optional, defaults to VEX_GATE_URL)
 *   VEX_CLIENT_ID     — M2M client ID (optional)
 *   VEX_CLIENT_SECRET — M2M client secret (optional, paired with VEX_CLIENT_ID)
 *   VEX_TOKEN         — static Bearer token (optional fallback)
 *   WORKFLOW_ID       — workflow to run (default: kaspi-payment-v1)
 *
 * If no auth vars are set, requests are sent with no Authorization header.
 * Ask your Vextura admin which mode your deployment requires.
 */
public class ExecuteWorkflow {

    public static void main(String[] args) throws InterruptedException {
        String gateUrl    = require("VEX_GATE_URL");
        String workflowId = System.getenv().getOrDefault("WORKFLOW_ID", "kaspi-payment-v1");

        AuthProvider auth = resolveAuth(gateUrl);
        UwfEngineClient client = UwfEngineClient.withEndpoint(gateUrl, auth);

        // ── 1. Health check ───────────────────────────────────────────────────
        HealthResponse health = client.healthCheck();
        System.out.println("Engine status : " + health.status);
        System.out.println("NATS          : " + (health.nats  != null ? health.nats.ok  : "n/a"));
        System.out.println("Redis         : " + (health.redis != null ? health.redis.ok : "n/a"));

        // ── 2. List available workflows ───────────────────────────────────────
        ListWorkflowsResponse list = client.listWorkflows();
        System.out.println("Workflows     : " + list.workflows.size() + " registered");
        list.workflows.forEach(w -> System.out.println("  • " + w.id + " — " + w.name));

        // ── 3. Execute ────────────────────────────────────────────────────────
        Map<String, Object> input = new HashMap<>();
        input.put("amount",   1000);
        input.put("currency", "KZT");
        input.put("sender",   "vex-user-guide-101");

        ExecuteWorkflowInput req = new ExecuteWorkflowInput();
        req.workflowId = workflowId;
        req.inputData  = input;

        System.out.println("\nExecuting workflow : " + workflowId);
        ExecutionResult result = client.executeWorkflow(req);
        System.out.println("Run ID             : " + result.runId);
        System.out.println("Initial status     : " + result.status);

        // ── 4. Poll until done (max 30s) ──────────────────────────────────────
        RunIdInput statusReq = new RunIdInput();
        statusReq.runId = result.runId;

        String finalStatus = result.status;
        for (int i = 0; i < 30; i++) {
            ExecutionStatus s = client.getExecutionStatus(statusReq);
            finalStatus = s.status;
            System.out.printf("  [%2ds] status=%-12s step=%s%n", i + 1, s.status, s.currentStep);
            if ("completed".equals(s.status) || "failed".equals(s.status)
                    || "cancelled".equals(s.status)) break;
            Thread.sleep(1_000);
        }

        System.out.println("\nFinal status       : " + finalStatus);
        System.exit("completed".equals(finalStatus) ? 0 : 1);
    }

    private static AuthProvider resolveAuth(String gateUrl) {
        String authUrl      = System.getenv("VEX_AUTH_URL");
        String clientId     = System.getenv("VEX_CLIENT_ID");
        String clientSecret = System.getenv("VEX_CLIENT_SECRET");
        String token        = System.getenv("VEX_TOKEN");

        if (clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank()) {
            String tokenEndpoint = (authUrl != null && !authUrl.isBlank()) ? authUrl : gateUrl;
            System.out.println("[auth] M2M client_credentials @ " + tokenEndpoint);
            return new M2MAuth(tokenEndpoint, clientId, clientSecret);
        }
        if (token != null && !token.isBlank()) {
            System.out.println("[auth] static Bearer token");
            return new BearerAuth(token);
        }
        System.out.println("[auth] no auth");
        return NoAuth.INSTANCE;
    }

    private static String require(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            System.err.println("ERROR: " + key + " is required");
            System.exit(1);
        }
        return v;
    }
}
