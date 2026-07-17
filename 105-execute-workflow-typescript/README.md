# 105 — Execute a Workflow with the TypeScript SDK

**SDK:** `@vextura/sdk-vex-uwf@0.1.0`

> For Java, see [101 — Execute a Workflow — Java SDK](../101-execute-workflow-java/README.md).

---

## Prerequisites

| Tool | Version |
|------|---------|
| Node.js | 18+ (ESM support required) |
| npm / pnpm | any recent |

No `vexctl` required for application code — auth is handled by the SDK automatically.

---

## What your Vextura admin gives you

Before running anything your admin provides these values:

| Variable | Description | Example |
|----------|-------------|---------|
| `VEX_GATE_URL` | vex-gate URL (single entry point for auth + workflows) | `http://vex-gate.your-cluster.internal:8080` |
| `VEX_TOKEN` | Bearer token for M2M auth | `eyJhbGci...` |
| `VEX_TENANT` | Your tenant slug | `my-org` |

For automated services, your admin may issue a long-lived M2M token or instruct you to fetch one via the `client_credentials` grant before constructing the client.

---

## How authentication works

The SDK accepts an async `token` callback — your code fetches and caches tokens however it prefers. The SDK calls the callback on every request; cache in a closure or a singleton to avoid hammering the auth endpoint:

```
Your app              vex-gate             workflow-api
    │                     │                     │
    │── POST /auth/token ──────────────────────>│
    │   grant_type=client_credentials           │
    │   client_id + client_secret               │
    │<── { access_token, expires_in: 3600 } ───│
    │                                           │
    │── POST /workflow/executions ─── Bearer──>│─→ workflow-api
    │── GET  /workflow/executions/{id} ────────>│─→ workflow-api
    │                                           │
    │  (token management is your responsibility — see Step 2)
```

---

## Step 1 — Add the dependency

```bash
npm install @vextura/sdk-vex-uwf
```

The package is published to GitHub Packages under the `@vextura` scope. Add the registry to your `.npmrc` if not already configured:

```ini
@vextura:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}
```

---

## Step 2 — Create the client

**Option A — Token from environment** (for scripts and backend services):

```typescript
import { VexUwfClient } from '@vextura/sdk-vex-uwf';

const client = VexUwfClient.withEndpoint(
  `${process.env.VEX_GATE_URL}/workflow`,
  { token: async () => process.env.VEX_TOKEN ?? '' },
  { tenant: async () => process.env.VEX_TENANT ?? 'default' }
);
```

**Option B — Token from auth endpoint** (M2M, production services):

```typescript
import { VexUwfClient } from '@vextura/sdk-vex-uwf';

let cachedToken = '';
let expiresAt = 0;

async function getToken(): Promise<string> {
  if (Date.now() < expiresAt - 60_000) return cachedToken;
  const res = await fetch(`${process.env.VEX_AUTH_URL}/auth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'client_credentials',
      client_id:  process.env.VEX_CLIENT_ID ?? '',
      client_secret: process.env.VEX_CLIENT_SECRET ?? '',
    }),
  });
  const { access_token, expires_in } = await res.json();
  cachedToken = access_token;
  expiresAt   = Date.now() + expires_in * 1000;
  return cachedToken;
}

const client = VexUwfClient.withEndpoint(
  `${process.env.VEX_GATE_URL}/workflow`,
  { token: getToken },
  { tenant: async () => process.env.VEX_TENANT ?? 'default' }
);
```

Ask your Vextura admin which option applies to your deployment.

> **Note:** The SDK endpoint must be anchored at `${VEX_GATE_URL}/workflow` — not the bare gate URL — because vex-gate fronts the workflow API at the `/workflow/*` prefix.

---

## Step 3 — Execute a workflow

```typescript
import type { ExecuteWorkflowInput } from '@vextura/sdk-vex-uwf';

const req: ExecuteWorkflowInput = {
  id: 'kaspi-payment-v1',
  input_data: {
    amount:   1000,
    currency: 'KZT',
    sender:   'my-app',
  },
};

const ack = await client.executeWorkflow(req);
console.log('Run ID:', ack.run_id);
console.log('Status:', ack.status);   // "pending" or "completed"
```

---

## Step 4 — Poll for completion

```typescript
const MAX_POLLS = 30;

for (let i = 0; i < MAX_POLLS; i++) {
  const s = await client.getExecutionStatus({ run_id: ack.run_id });
  console.log(`[${i + 1}s] status=${s.status}`);
  if (s.status === 'completed' || s.status === 'failed') break;
  await new Promise(r => setTimeout(r, 1_000));
}
```

---

## Step 5 — Run the full example

```bash
# Clone this repo
git clone https://github.com/vextura/vex-user-guide.git
cd vex-user-guide/105-execute-workflow-typescript

# Set the values your admin gave you
export VEX_GATE_URL=http://vex-gate.your-cluster.internal:8080
export VEX_TOKEN=your-bearer-token
export VEX_TENANT=my-org
export WORKFLOW_ID=kaspi-payment-v1

# Install and run
npm install
node --input-type=module < index.mjs
```

Expected output:
```
Run ID : run-1780414376887205751
Status : pending
  [ 1s] status=completed

Final status : completed
```

---

## Browser / React usage

In a browser context, pass live values via closures — the provider callbacks are called on every SDK request, so tenant and token changes propagate without rebuilding the client:

```typescript
import { VexUwfClient } from '@vextura/sdk-vex-uwf';

// Construct once (e.g. in a React context provider or module singleton)
const client = VexUwfClient.withEndpoint(
  `${gateUrl}/workflow`,
  { token: async () => authStore.getToken() },
  { tenant: async () => authStore.getTenant() }
);

// Use inside event handlers or useEffect
async function handleSubmit() {
  const ack = await client.executeWorkflow({
    id: 'my-workflow-id',
    input_data: { key: 'value' },
  });
  console.log('Started:', ack.run_id);
}
```

Do not call `executeWorkflow` at module load time — wait until a user action or a mounted effect so that auth is resolved.

---

## SDK reference

| Method | Description |
|--------|-------------|
| `client.healthCheck()` | Check engine + NATS + Redis status |
| `client.listWorkflows()` | List all registered workflow definitions |
| `client.getWorkflow(req)` | Get a single workflow definition by ID |
| `client.executeWorkflow(req)` | Submit workflow, returns `run_id` immediately |
| `client.getExecutionStatus(req)` | Poll execution status by `run_id` |
| `client.listExecutions(req)` | List past executions (paginated) |
| `client.cancelExecution(req)` | Cancel a running execution |

Input / output types are generated from the vex-uwf Smithy contract and exported from `@vextura/sdk-vex-uwf`. Import them directly — do not define parallel interfaces.

---

## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `VEX_GATE_URL is required` | Env var not set | Set all env vars from your admin |
| `401 Unauthorized` | Token empty or expired | Verify `VEX_TOKEN` or fix the `getToken()` cache logic |
| `403 Forbidden` | Wrong tenant or missing IAM role | Confirm `VEX_TENANT` with your admin |
| `Connection refused on VEX_GATE_URL` | Wrong URL or no network access | Confirm you're on the cluster network and `VEX_GATE_URL` is correct |
| `404 workflow not found` | Wrong workflow ID | Call `client.listWorkflows()` to see registered IDs |
| `Cannot find package '@vextura/sdk-vex-uwf'` | Missing `.npmrc` registry config | Add the `@vextura` GitHub Packages registry (see Step 1) |

---

## See also

- [101 — Execute a Workflow — Java SDK](../101-execute-workflow-java/README.md)
- vex-uwf Smithy contract: `unified-workflow-go/smithy/vex-uwf.smithy`
- Generated SDK source: `unified-workflow-go/sdk/typescript/`
- [106 — Execute a Workflow — Go SDK *(coming soon)*]()
- [107 — Deploy a New Service *(coming soon)*]()
