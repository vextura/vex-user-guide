package ai.vextura.examples;

import ai.vextura.uwf_engine.UwfEngineClient;
import ai.vextura.uwf_engine.models.*;
import ai.vextura.uwf_engine.runtime.BearerAuth;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal example: execute a workflow and wait for it to complete.
 *
 * Required environment variables:
 *   UWF_ENDPOINT  — resolved via: vexctl rip resolve uwf-engine <region>
 *   VEX_TOKEN     — your Bearer token:  vexctl auth token
 *   WORKFLOW_ID   — workflow to run, e.g. kaspi-payment-v1
 */
public class ExecuteWorkflow {

    public static void main(String[] args) throws InterruptedException {
        String endpoint   = require("UWF_ENDPOINT",
                "Resolve it with: vexctl rip resolve uwf-engine <region>");
        String token      = require("VEX_TOKEN",
                "Get it with:     vexctl auth token");
        String workflowId = System.getenv().getOrDefault("WORKFLOW_ID", "kaspi-payment-v1");

        // ── 1. Create client ──────────────────────────────────────────────────
        UwfEngineClient client = UwfEngineClient.withEndpoint(endpoint, new BearerAuth(token));

        // ── 2. Quick health check ─────────────────────────────────────────────
        HealthResponse health = client.healthCheck();
        System.out.println("Engine status : " + health.status);

        // ── 3. List available workflows ───────────────────────────────────────
        ListWorkflowsResponse list = client.listWorkflows();
        System.out.println("Workflows     : " + list.workflows.size() + " registered");
        list.workflows.forEach(w -> System.out.println("  • " + w.id + " — " + w.name));

        // ── 4. Build input payload ────────────────────────────────────────────
        Map<String, Object> input = new HashMap<>();
        input.put("amount",   1000);
        input.put("currency", "KZT");
        input.put("sender",   "vex-user-guide-example");

        ExecuteWorkflowInput req = new ExecuteWorkflowInput();
        req.workflowId = workflowId;
        req.inputData  = input;

        // ── 5. Execute ────────────────────────────────────────────────────────
        System.out.println("\nExecuting workflow: " + workflowId);
        ExecutionResult result = client.executeWorkflow(req);
        System.out.println("Run ID        : " + result.runId);
        System.out.println("Initial status: " + result.status);

        // ── 6. Poll until done (max 30s) ──────────────────────────────────────
        RunIdInput statusReq = new RunIdInput();
        statusReq.runId = result.runId;

        String finalStatus = result.status;
        for (int i = 0; i < 30; i++) {
            ExecutionStatus s = client.getExecutionStatus(statusReq);
            finalStatus = s.status;
            System.out.printf("  [%2ds] status=%s step=%s%n", i + 1, s.status, s.currentStep);

            if ("completed".equals(s.status) || "failed".equals(s.status)
                    || "cancelled".equals(s.status)) {
                break;
            }
            Thread.sleep(1_000);
        }

        System.out.println("\nFinal status  : " + finalStatus);
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
