package ai.vextura.examples;

import ai.vextura.uwf_engine.UwfEngineClient;
import ai.vextura.uwf_engine.models.*;
import ai.vextura.uwf_engine.runtime.M2MAuth;

import java.util.HashMap;
import java.util.Map;

/**
 * Production-ready example: authenticate with M2MAuth (auto-refresh),
 * execute a workflow, and poll until completion.
 *
 * Required environment variables — provided by your Vextura admin:
 *   VEX_GATE_URL      vex-gate base URL,  e.g. http://vex-gate.your-cluster.internal:8080
 *   VEX_CLIENT_ID     your client ID
 *   VEX_CLIENT_SECRET your client secret
 *   WORKFLOW_ID       workflow to run,    e.g. kaspi-payment-v1
 */
public class ExecuteWorkflow {

    public static void main(String[] args) throws InterruptedException {
        String gateUrl = require("VEX_GATE_URL", "vex-gate base URL — provided by your Vextura admin");

        // ── 1. Auth — M2MAuth fetches a JWT automatically and refreshes before expiry ──
        M2MAuth auth = new M2MAuth(
            gateUrl,
            require("VEX_CLIENT_ID",     "your M2M client ID — provided by your Vextura admin"),
            require("VEX_CLIENT_SECRET", "your M2M client secret — provided by your Vextura admin")
        );

        // ── 2. Client ─────────────────────────────────────────────────────────────────
        String workflowId = System.getenv().getOrDefault("WORKFLOW_ID", "kaspi-payment-v1");

        UwfEngineClient client = UwfEngineClient.withEndpoint(gateUrl, auth);

        // ── 3. Health check ───────────────────────────────────────────────────────────
        HealthResponse health = client.healthCheck();
        System.out.println("Engine status : " + health.status);
        System.out.println("NATS          : " + (health.nats  != null ? health.nats.ok  : "n/a"));
        System.out.println("Redis         : " + (health.redis != null ? health.redis.ok : "n/a"));

        // ── 4. List available workflows ───────────────────────────────────────────────
        ListWorkflowsResponse list = client.listWorkflows();
        System.out.println("Workflows     : " + list.workflows.size() + " registered");
        list.workflows.forEach(w -> System.out.println("  • " + w.id + " — " + w.name));

        // ── 5. Build input payload ────────────────────────────────────────────────────
        Map<String, Object> input = new HashMap<>();
        input.put("amount",   1000);
        input.put("currency", "KZT");
        input.put("sender",   "vex-user-guide-example");

        ExecuteWorkflowInput req = new ExecuteWorkflowInput();
        req.workflowId = workflowId;
        req.inputData  = input;

        // ── 6. Execute ────────────────────────────────────────────────────────────────
        System.out.println("\nExecuting workflow : " + workflowId);
        ExecutionResult result = client.executeWorkflow(req);
        System.out.println("Run ID             : " + result.runId);
        System.out.println("Initial status     : " + result.status);

        // ── 7. Poll until done (max 30s) ──────────────────────────────────────────────
        RunIdInput statusReq = new RunIdInput();
        statusReq.runId = result.runId;

        String finalStatus = result.status;
        for (int i = 0; i < 30; i++) {
            ExecutionStatus s = client.getExecutionStatus(statusReq);
            finalStatus = s.status;
            System.out.printf("  [%2ds] status=%-12s step=%s%n", i + 1, s.status, s.currentStep);

            if ("completed".equals(s.status) || "failed".equals(s.status)
                    || "cancelled".equals(s.status)) {
                break;
            }
            Thread.sleep(1_000);
        }

        System.out.println("\nFinal status       : " + finalStatus);
        System.exit("completed".equals(finalStatus) ? 0 : 1);
    }

    private static String require(String envVar, String hint) {
        String v = System.getenv(envVar);
        if (v == null || v.isBlank()) {
            System.err.println("ERROR: " + envVar + " is required.");
            System.err.println("       " + hint);
            System.exit(1);
        }
        return v;
    }
}
