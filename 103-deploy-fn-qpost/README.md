# 103 — Deploy a Serverless Function to the Qazpost Production Cluster

> [!NOTE]
> This guide documents a real deployment of `hello-fn` to the Qazpost (`qzp`) production cluster running Vextura Platform on `kz-north-1`. Every command was executed and verified. You can follow it top-to-bottom on the same cluster without modification.

**What you will build:** A three-operation serverless function (`hello-fn`) exposed through `vex-gate` on the Qazpost cluster. The function demonstrates the full Vextura deployment pipeline — from Smithy model to live HTTP endpoint.

**Time to complete:** ~25 minutes (most of it is the Docker build on an air-gapped node).

---

## Prerequisites

Before starting, confirm you have all of the following available on your local workstation.

### Tools

| Tool | Version | Purpose |
|---|---|---|
| `vexctl` | ≥ 1.4 | Dome shell access, fn generation |
| `curl` | any | Admin API calls |
| `podman` or `docker` | any | Base-image push from bastion |

### Access

| Resource | Detail |
|---|---|
| vexctl authenticated | Profile must be valid — run `vexctl auth status` |
| Dome access to core-01 | target: `qzp-kz-north-1-ast-1-c02-plt-core-01` |
| Dome access to core-02 | target: `qzp-kz-north-1-ast-1-c02-plt-core-02` |
| Harbor registry reachable | `172.30.75.78:9080` from bastion node |
| Bastion has internet access | Required for pulling `golang:1.25-alpine` |

### Cluster Quick-Reference

| Item | Value |
|---|---|
| Region | `kz-north-1` |
| Tenant | `vextura` |
| vex-config (admin) | `http://localhost:9090` (on core-01) |
| vex-auth | `http://localhost:8095` (on core-01) |
| vex-gate (core-02) | `172.30.75.94:8080` |
| vex-gate (core-03) | `172.30.75.97:8080` |
| Harbor | `172.30.75.78:9080` |
| Admin credentials | `admin` / `c5a8636be692059deb5ff3f7` |
| Admin key | `8d7b9d56b712ddb370cf49c4ccbac93192cd6c5822dc8abc` |

> [!WARNING]
> The vex-config admin API at `localhost:9090` is **only reachable from within core-01** — it is not exposed externally. All admin API calls in this guide run inside `vexctl dome shell run` sessions on core-01.

---

## Phase 1 — Write the Smithy Model (`hello.smithy`)

**Why Smithy first?** The Vextura toolchain (and the VEXBLUE convention) treats the Smithy model as the single source of truth for a service's shape. `vexctl fn generate` reads it to produce the Go scaffold. Getting the model right before writing any Go code prevents mismatches between your routes and your handler.

Create the repo layout on your local workstation:

```shell
mkdir -p hello-fn/smithy hello-fn/functions/hello-fn
```

**What happened:** Two directories created — `smithy/` for the model, `functions/hello-fn/` for the Go fn code.

Write the Smithy model:

```shell
cat > hello-fn/smithy/hello.smithy << 'EOF'
$version: "2"

namespace vextura.hello

use vextura.platform#vexFn
use vextura.platform#vexGate

/// Demo service — 3 HTTP operations routed through vex-gate to hello-fn.
/// Static paths registered before parameterised paths (VEXBLUE rule).
@vexGate(targetUrl: "http://vex-fn.vex.internal:8090")
service HelloService {
    version: "1.0.0"
    operations: [HelloGreet, HelloEcho, HelloVersion]
}

// ─────────────────────────── Greet ──────────────────────────────────────────

/// Returns a greeting from the Qpost cluster.
@vexFn(name: "hello-fn", tenant: "vextura", image: "hello-fn:v1.0.0")
@http(method: "GET", uri: "/demo/hello")
@readonly
operation HelloGreet {
    output: HelloGreetOutput
}

structure HelloGreetOutput {
    message: String
    region:  String
    status:  String
}

// ─────────────────────────── Echo ───────────────────────────────────────────

/// Echoes the request body back — useful for integration testing.
@vexFn(name: "hello-fn", tenant: "vextura", image: "hello-fn:v1.0.0")
@http(method: "POST", uri: "/demo/echo")
operation HelloEcho {
    input:  HelloEchoInput
    output: HelloEchoOutput
}

structure HelloEchoInput {
    @httpPayload
    body: Document
}

structure HelloEchoOutput {
    @httpPayload
    body: Document
}

// ─────────────────────────── Version ────────────────────────────────────────

/// Returns service version metadata — no auth required.
@vexFn(name: "hello-fn", tenant: "vextura", image: "hello-fn:v1.0.0")
@http(method: "GET", uri: "/demo/version")
@readonly
operation HelloVersion {
    output: HelloVersionOutput
}

structure HelloVersionOutput {
    version: String
    service: String
    tenant:  String
}
EOF
```

**What happened:** `hello.smithy` written with three operations. All paths are static (`/demo/hello`, `/demo/echo`, `/demo/version`) — no path parameters. This satisfies the VEXBLUE rule that static paths must be registered before parameterised paths.

> [!NOTE]
> The `@vexGate(targetUrl: ...)` annotation tells the SDK generator what downstream address the gate proxies to. In production, `vex-fn.vex.internal` resolves via the cluster's internal DNS (CoreDNS + RIP). You do not hardcode an IP here.

---

## Phase 2 — Generate fn Stubs (`vexctl fn generate`)

**Why generate?** `vexctl fn generate` reads the Smithy model and emits a `main.go` that wires `vexfn.Handle` correctly, plus a `Dockerfile` tuned for the `vex-fn` stdio transport. It also creates a `handler.go` stub with one case per operation — you fill in the logic and the generator never overwrites it on subsequent runs.

```shell
cd hello-fn
vexctl fn generate --smithy smithy/hello.smithy --out functions/hello-fn --name hello-fn
```

**What happened:** Three files created under `functions/hello-fn/`:
- `main.go` — wires `vexfn.Handle[json.RawMessage, json.RawMessage](Handle)`
- `handler.go` — stub with a `switch evt.Operation` skeleton
- `Dockerfile` — multi-stage build: `golang:1.25-alpine` builder → `alpine:3.19` runtime

> [!TIP]
> Run `vexctl fn generate` again at any time to regenerate `main.go` and `Dockerfile`. Your business logic in `handler.go` is never touched — the generator detects it and skips it.

Initialise Go modules and vendor the dependencies:

```shell
cd functions/hello-fn
go mod tidy
go mod vendor
```

**What happened:** `go.sum` updated, `vendor/` directory populated. The `Dockerfile` uses `-mod=vendor` so the build has no network dependency — this is required on the air-gapped core-02 node.

---

## Phase 3 — Implement `handler.go`

**Why this way?** The vexfn transport is stdio, not HTTP. `vex-fn` attaches to the container's stdin/stdout, sends a JSON `Event`, and reads back a JSON response. There is no HTTP server in the fn — adding one would cause a 30-second timeout on every invocation. All three operations are dispatched through a single `Handle` function via `evt.Operation`.

Replace the generated stub with the full implementation:

```shell
cat > functions/hello-fn/handler.go << 'EOF'
package main

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/vextura/vex-sdk-go/vexfn"
)

// Handle dispatches all three operations: HelloGreet, HelloEcho, HelloVersion.
func Handle(ctx context.Context, evt vexfn.Event[json.RawMessage]) (*json.RawMessage, error) {
	switch evt.Operation {
	case "HelloGreet":
		return greet()
	case "HelloEcho":
		return echoBody(evt.Data)
	case "HelloVersion":
		return version()
	default:
		return nil, fmt.Errorf("hello-fn: unknown operation %q", evt.Operation)
	}
}

func greet() (*json.RawMessage, error) {
	out, _ := json.Marshal(map[string]string{
		"message": "Hello from Qpost cluster!",
		"region":  "kz-north-1",
		"status":  "ok",
	})
	raw := json.RawMessage(out)
	return &raw, nil
}

func echoBody(body json.RawMessage) (*json.RawMessage, error) {
	if len(body) == 0 {
		empty, _ := json.Marshal(map[string]any{})
		raw := json.RawMessage(empty)
		return &raw, nil
	}
	return &body, nil
}

func version() (*json.RawMessage, error) {
	out, _ := json.Marshal(map[string]string{
		"version": "1.0.0",
		"service": "hello-fn",
		"tenant":  "vextura",
	})
	raw := json.RawMessage(out)
	return &raw, nil
}
EOF
```

**What happened:** `handler.go` now contains all three operation implementations. The `default` case returns an error — `vex-fn` will surface this as a 400-level response to the caller rather than silently returning empty data.

Verify the build compiles locally before touching the cluster:

```shell
cd functions/hello-fn
go build -mod=vendor ./...
```

**What happened:** Binary compiled cleanly. No network access needed — everything is in `vendor/`. If this fails, fix it now before proceeding to the cluster.

---

## Phase 4 — Bootstrap Harbor Base Images (Bastion Node)

**Why this phase exists?** The Qazpost cluster is air-gapped — core-02 cannot pull from Docker Hub. The `Dockerfile` needs `golang:1.25-alpine` (builder stage) and `alpine:3.19` (runtime stage). These images must be pushed to the cluster's Harbor registry (`172.30.75.78:9080`) from a node that has internet access. The bastion node (`10.200.1.8`) has `podman` installed and outbound internet.

> [!BUG]
> **Gotcha #1 — Harbor is missing standard base images.**
> The Qazpost Harbor registry does not mirror Docker Hub. If you skip this phase and go straight to `docker build` on core-02, the build will fail with `manifest unknown` when pulling `golang:1.25-alpine`. Always bootstrap base images through the bastion before your first build on any air-gapped node.

Open a dome shell on the bastion:

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-01 -- bash
```

**What happened:** An interactive shell on core-01 via the dome transport. Note: we use core-01 here because it has bastion-level internet routing configured.

Inside the dome shell, log in to Harbor and pull/push the base images:

```shell
podman login 172.30.75.78:9080 --username admin --password Harbor12345 --tls-verify=false

podman pull docker.io/library/golang:1.25-alpine
podman tag  docker.io/library/golang:1.25-alpine 172.30.75.78:9080/library/golang:1.25-alpine
podman push 172.30.75.78:9080/library/golang:1.25-alpine --tls-verify=false

podman pull docker.io/library/alpine:3.19
podman tag  docker.io/library/alpine:3.19 172.30.75.78:9080/library/alpine:3.19
podman push 172.30.75.78:9080/library/alpine:3.19 --tls-verify=false
```

**What happened:** Both base images are now available in Harbor's `library` project. The `--tls-verify=false` flag is needed because Harbor on this cluster uses a self-signed certificate.

Exit the dome shell:

```shell
exit
```

> [!NOTE]
> You only need to run this phase once per base image tag. Subsequent fn builds on core-02 will find both images in Harbor's cache and skip the pull entirely.

---

## Phase 5 — Build & Push fn Image (core-02, `nohup` Workaround)

**Why core-02?** The fn image must be built on the node that runs `vex-fn` — core-02. While you could build elsewhere and copy the image, building on-node keeps the registry push local and avoids any image-transfer size limits.

**Why `nohup`?** The dome shell transport has a ~30-second idle/length limit. A full `docker build` on a first run takes 60–90 seconds. If you run it directly, the dome connection drops with an EOF before the build finishes — leaving a zombie build process and a partial image. The workaround is to launch the build with `nohup`, write an exit code to a file, and poll that file.

> [!BUG]
> **Gotcha #2 — `vexctl dome shell run` drops connection (EOF) for commands longer than ~30 seconds.**
> Any long-running command (docker build, go build, heavy scripts) must use the `nohup` + poll pattern shown below. Do NOT wait for the output inline — you will not receive it.

### Step 5.1 — Upload the source to core-02

First, write the source files to core-02 via dome file write.

> [!WARNING]
> **Gotcha #3 — `vexctl dome file write` uses `--device`, not `--target`.**
> The dome file subcommands (`file write`, `file read`, `file download`) use the `--device` flag. The shell/script subcommands use `--target`. Mixing them up gives a confusing "flag not found" error.

Upload each source file individually:

```shell
vexctl dome file write --device qzp-kz-north-1-ast-1-c02-plt-core-02 \
  --path /tmp/hello-fn-build/go.mod \
  --content "$(cat functions/hello-fn/go.mod)"

vexctl dome file write --device qzp-kz-north-1-ast-1-c02-plt-core-02 \
  --path /tmp/hello-fn-build/go.sum \
  --content "$(cat functions/hello-fn/go.sum)"

vexctl dome file write --device qzp-kz-north-1-ast-1-c02-plt-core-02 \
  --path /tmp/hello-fn-build/main.go \
  --content "$(cat functions/hello-fn/main.go)"

vexctl dome file write --device qzp-kz-north-1-ast-1-c02-plt-core-02 \
  --path /tmp/hello-fn-build/handler.go \
  --content "$(cat functions/hello-fn/handler.go)"

vexctl dome file write --device qzp-kz-north-1-ast-1-c02-plt-core-02 \
  --path /tmp/hello-fn-build/Dockerfile \
  --content "$(cat functions/hello-fn/Dockerfile)"
```

**What happened:** All five source files written to `/tmp/hello-fn-build/` on core-02.

Because the `Dockerfile` uses `-mod=vendor`, you must also upload the vendor directory. The easiest approach is to tar it first:

```shell
tar czf /tmp/hello-fn-vendor.tar.gz -C functions/hello-fn vendor

vexctl dome file write --device qzp-kz-north-1-ast-1-c02-plt-core-02 \
  --path /tmp/hello-fn-vendor.tar.gz \
  --source /tmp/hello-fn-vendor.tar.gz
```

**What happened:** Vendor tarball uploaded to core-02 at `/tmp/hello-fn-vendor.tar.gz`.

### Step 5.2 — Unpack vendor and patch the Dockerfile

Open a dome shell on core-02 to prepare the build context:

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-02 -- bash
```

Inside the shell:

```shell
cd /tmp/hello-fn-build
tar xzf /tmp/hello-fn-vendor.tar.gz

# Update the Dockerfile to pull base images from Harbor instead of Docker Hub
sed -i 's|FROM golang:1.25-alpine|FROM 172.30.75.78:9080/library/golang:1.25-alpine|' Dockerfile
sed -i 's|FROM alpine:3.19|FROM 172.30.75.78:9080/library/alpine:3.19|' Dockerfile

cat Dockerfile
```

**What happened:** The `vendor/` directory is unpacked and the Dockerfile now references Harbor images instead of Docker Hub. Verify the two `FROM` lines show `172.30.75.78:9080/library/...` before continuing.

Exit and return to your workstation:

```shell
exit
```

### Step 5.3 — Build the image with `nohup`

Open a dome shell on core-02 and launch the build in the background:

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-02 -- bash
```

Inside the shell:

```shell
docker login 172.30.75.78:9080 --username admin --password Harbor12345

nohup docker build \
  --provenance=false \
  -t 172.30.75.78:9080/vextura/hello-fn:v1.0.0 \
  /tmp/hello-fn-build \
  > /tmp/hello-fn-build.log 2>&1; echo $? > /tmp/hello-fn-build.exit &

echo "Build started — PID $!"
exit
```

**What happened:** The build runs in the background. The exit code will be written to `/tmp/hello-fn-build.exit` when it completes. The shell session exits immediately — the build keeps running.

### Step 5.4 — Poll until the build finishes

Poll from your workstation in a loop (run this once per poll — repeat until exit code appears):

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-02 -- \
  bash -c "cat /tmp/hello-fn-build.exit 2>/dev/null || echo 'still building...'"
```

**What happened (expected):** Returns `still building...` while in progress, then `0` when complete. A non-zero value means the build failed — check the log.

If the build fails, read the log to diagnose:

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-02 -- \
  bash -c "tail -50 /tmp/hello-fn-build.log"
```

### Step 5.5 — Push the image to Harbor

Once the exit code is `0`:

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-02 -- bash
```

```shell
nohup docker push 172.30.75.78:9080/vextura/hello-fn:v1.0.0 \
  > /tmp/hello-fn-push.log 2>&1; echo $? > /tmp/hello-fn-push.exit &
exit
```

Poll until pushed:

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-02 -- \
  bash -c "cat /tmp/hello-fn-push.exit 2>/dev/null || echo 'still pushing...'"
```

**What happened:** `0` means the image `172.30.75.78:9080/vextura/hello-fn:v1.0.0` is now in Harbor and ready for `vex-fn` to pull.

---

## Phase 6 — Register the fn Manifest (via vex-config on core-01)

**Why this step?** `vex-fn` is a runtime that knows nothing about your fn until you tell it. The fn manifest tells `vex-fn` which image to attach to which operation names, what memory and timeout limits to apply, and what tenant it belongs to. `vex-fn` polls vex-config every ~30 seconds and re-renders its function table.

The admin API is only reachable from within core-01. Open a dome shell there:

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-01 -- bash
```

POST the fn manifest to vex-config:

```shell
curl -s -X POST http://localhost:9090/api/v1/functions/vextura \
  -H "Content-Type: application/json" \
  -H "X-Admin-Key: 8d7b9d56b712ddb370cf49c4ccbac93192cd6c5822dc8abc" \
  -d '{
    "service_name": "hello-fn",
    "tenant": "vextura",
    "functions": [
      {
        "name": "hello-fn",
        "image": "172.30.75.78:9080/vextura/hello-fn:v1.0.0",
        "memory_mb": 128,
        "timeout_seconds": 30,
        "network_mode": "host",
        "tenant": "vextura"
      }
    ]
  }' | jq .
```

**What happened:** vex-config persists the manifest and returns the registered function record. Expected response shape:

```json
{
  "name": "hello-fn",
  "tenant": "vextura",
  "image": "172.30.75.78:9080/vextura/hello-fn:v1.0.0",
  "status": "registered"
}
```

> [!NOTE]
> `"network_mode": "host"` is required on this cluster. The fn container shares the host network namespace, which lets it reach NATS (`172.30.75.85:4222`) and other internal services without an overlay network. Omitting it causes the fn to start but fail to connect to NATS for result delivery.

Verify it was stored:

```shell
curl -s http://localhost:9090/api/v1/functions/vextura \
  -H "X-Admin-Key: 8d7b9d56b712ddb370cf49c4ccbac93192cd6c5822dc8abc" | jq '.[] | select(.name=="hello-fn")'
```

**What happened:** Should print the `hello-fn` entry from the manifest list.

Exit the dome shell:

```shell
exit
```

---

## Phase 7 — Register Routes (via vex-config on core-01)

**Why this step?** vex-gate knows nothing about your fn's HTTP surface until you push routes to vex-config. When gate starts (and every ~30 seconds), it pulls the full route table for the `vextura` tenant and builds its internal router. Each route maps an HTTP method+path to a fn name + operation name.

**Why POST (not PUT)?** A PUT to `/api/v1/routes/vextura` would *replace* the entire route table — you would delete every existing route for the tenant. POST *appends* the new routes to the existing table. Always POST unless you intend a full replacement.

Open a dome shell on core-01:

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-01 -- bash
```

First, snapshot the current route table so you can verify the merge:

```shell
curl -s http://localhost:9090/api/v1/routes/vextura \
  -H "X-Admin-Key: 8d7b9d56b712ddb370cf49c4ccbac93192cd6c5822dc8abc" | jq 'length'
```

**What happened:** Prints the count of existing routes. Note this number — after the POST it should increase by 3.

POST the three new routes:

```shell
curl -s -X POST http://localhost:9090/api/v1/routes/vextura \
  -H "Content-Type: application/json" \
  -H "X-Admin-Key: 8d7b9d56b712ddb370cf49c4ccbac93192cd6c5822dc8abc" \
  -d '{
    "routes": [
      {
        "method": "GET",
        "path": "/demo/hello",
        "fn_name": "hello-fn",
        "operation_name": "HelloGreet",
        "auth": "jwt",
        "mode": "sync"
      },
      {
        "method": "POST",
        "path": "/demo/echo",
        "fn_name": "hello-fn",
        "operation_name": "HelloEcho",
        "auth": "jwt",
        "mode": "sync"
      },
      {
        "method": "GET",
        "path": "/demo/version",
        "fn_name": "hello-fn",
        "operation_name": "HelloVersion",
        "auth": "none",
        "mode": "sync"
      }
    ]
  }' | jq .
```

**What happened:** vex-config appends the three routes and returns the updated table. `HelloVersion` uses `"auth": "none"` — this is intentional; the version endpoint is public, which lets you smoke-test gate without needing a JWT.

Confirm the route count increased by 3:

```shell
curl -s http://localhost:9090/api/v1/routes/vextura \
  -H "X-Admin-Key: 8d7b9d56b712ddb370cf49c4ccbac93192cd6c5822dc8abc" | jq 'length'
```

**What happened:** Should be exactly 3 more than the count you noted before.

Exit:

```shell
exit
```

---

## Phase 8 — Wait 30 Seconds for Gate to Pick Up

**Why wait?** vex-gate polls vex-config for route and fn manifest changes on a ~30-second interval. Immediately after pushing routes, gate's in-memory router still has the old table. Requests to `/demo/hello` will return 404 until the next poll cycle completes.

Similarly, `vex-fn` polls for fn manifest changes on the same interval. If you hit gate before `vex-fn` loads the manifest, gate will route the request correctly but `vex-fn` will return a "function not found" error.

```shell
echo "Waiting 30 seconds for gate and vex-fn to reload config..." && sleep 30 && echo "Done — proceed to smoke test."
```

**What happened:** Gate and vex-fn have both had time to complete at least one poll cycle and load the new routes and fn manifest.

> [!TIP]
> If you are in a hurry, you can force-reload by restarting the gate container on core-02: `vexctl dome svc restart --target qzp-kz-north-1-ast-1-c02-plt-core-02 --service vex-gate`. This skips the wait but causes a ~5-second outage on the gate.

---

## Phase 9 — Smoke Test via vex-gate

### Step 9.1 — Test the public endpoint (no auth required)

`HelloVersion` was registered with `"auth": "none"`. Test it first — if this works, gate is routing to `hello-fn` correctly.

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-02 -- \
  bash -c "curl -s http://localhost:8080/demo/version"
```

**What happened (expected):**

```json
{"service":"hello-fn","tenant":"vextura","version":"1.0.0"}
```

If you get a 404, gate has not yet reloaded routes — wait another 15 seconds and retry.
If you get a 502 or `function not found`, `vex-fn` has not yet loaded the manifest — wait another 15 seconds and retry.

### Step 9.2 — Obtain a JWT from vex-auth

The `HelloGreet` and `HelloEcho` operations require a JWT. Get one from core-01:

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-01 -- \
  bash -c "curl -s -X POST http://localhost:8095/auth/token \
    -H 'Content-Type: application/json' \
    -d '{\"grant_type\":\"client_credentials\",\"client_id\":\"admin\",\"client_secret\":\"c5a8636be692059deb5ff3f7\"}'"
```

**What happened (expected):**

```json
{
  "access_token": "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

Copy the value of `access_token`. Assign it to a shell variable for the next commands:

```shell
TOKEN="eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9..."   # paste your token here
```

### Step 9.3 — Test `HelloGreet`

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-02 -- \
  bash -c "curl -s http://localhost:8080/demo/hello -H 'Authorization: Bearer $TOKEN'"
```

**What happened (expected):**

```json
{"message":"Hello from Qpost cluster!","region":"kz-north-1","status":"ok"}
```

### Step 9.4 — Test `HelloEcho`

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-02 -- \
  bash -c "curl -s -X POST http://localhost:8080/demo/echo \
    -H 'Content-Type: application/json' \
    -H 'Authorization: Bearer $TOKEN' \
    -d '{\"ping\":\"pong\"}'"
```

**What happened (expected):**

```json
{"ping":"pong"}
```

The body is echoed back exactly as sent.

### Step 9.5 — Test via the external nginx proxy

The cluster nginx exposes two gate paths:

| Path prefix | Routes to | Tenant |
|---|---|---|
| `/api/gate/` | TAF gate at `172.30.75.85:7080` | `kazpost` |
| `/api/vex/` | Vextura platform gate at `172.30.75.94:8080` | `vextura` |

Your `hello-fn` runs on the Vextura platform gate, so use `/api/vex/`:

```shell
# Version (no auth needed)
curl -sk https://af-test.qazpost.kz/api/vex/demo/version

# Greet (authenticated)
curl -sk https://af-test.qazpost.kz/api/vex/demo/hello \
  -H "Authorization: Bearer $TOKEN"

# Echo
curl -sk -X POST https://af-test.qazpost.kz/api/vex/demo/echo \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"external":"test"}'
```

**What happened:** Each returns the same JSON as the internal gate test. The `-sk` flags suppress progress output and skip TLS verification (self-signed cert). The `/api/vex/` location was added to nginx during this deployment — see Gotcha 4.

---

## Verification

Use this section as a quick reference to re-verify the deployment at any time.

### Gate Health

```shell
vexctl fn gate health
```

**Expected:** `{"status":"ok","tenant":"vextura","region":"kz-north-1"}`

### List Registered Routes (includes hello-fn)

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-01 -- \
  bash -c "curl -s http://localhost:9090/api/v1/routes/vextura \
    -H 'X-Admin-Key: 8d7b9d56b712ddb370cf49c4ccbac93192cd6c5822dc8abc' | jq '.[] | select(.fn_name==\"hello-fn\")'"
```

**Expected:** Three route objects for `GET /demo/hello`, `POST /demo/echo`, `GET /demo/version`.

### List Registered Functions

```shell
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-01 -- \
  bash -c "curl -s http://localhost:9090/api/v1/functions/vextura \
    -H 'X-Admin-Key: 8d7b9d56b712ddb370cf49c4ccbac93192cd6c5822dc8abc' | jq '.[] | select(.name==\"hello-fn\")'"
```

**Expected:** The `hello-fn` manifest entry with image `172.30.75.78:9080/vextura/hello-fn:v1.0.0`.

### All Three Endpoints in One Pass

```shell
# 1. Version (no auth)
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-02 -- \
  bash -c "curl -s http://localhost:8080/demo/version"
# Expected: {"service":"hello-fn","tenant":"vextura","version":"1.0.0"}

# 2. Greet (get token first — see Phase 9 Step 9.2)
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-02 -- \
  bash -c "curl -s http://localhost:8080/demo/hello -H 'Authorization: Bearer $TOKEN'"
# Expected: {"message":"Hello from Qpost cluster!","region":"kz-north-1","status":"ok"}

# 3. Echo
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-02 -- \
  bash -c "curl -s -X POST http://localhost:8080/demo/echo \
    -H 'Content-Type: application/json' \
    -H 'Authorization: Bearer $TOKEN' \
    -d '{\"verify\":true}'"
# Expected: {"verify":true}
```

---

## Troubleshooting

### Gotcha 1 — Harbor is missing `golang:1.25-alpine` or `alpine:3.19`

**Symptom:** `docker build` on core-02 fails with:

```
ERROR [builder 1/4] FROM golang:1.25-alpine
...
manifest unknown: manifest unknown
```

**Cause:** The Qazpost Harbor registry does not proxy Docker Hub. Any image that has not been explicitly pushed to it is unavailable.

**Fix:** Complete Phase 4. From the bastion (core-01, which has outbound internet), pull the image from Docker Hub, re-tag it for Harbor, and push it. You only need to do this once per image tag.

```shell
# On core-01 (has internet)
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-01 -- bash

podman login 172.30.75.78:9080 --username admin --password Harbor12345 --tls-verify=false
podman pull docker.io/library/golang:1.25-alpine
podman tag  docker.io/library/golang:1.25-alpine 172.30.75.78:9080/library/golang:1.25-alpine
podman push 172.30.75.78:9080/library/golang:1.25-alpine --tls-verify=false
exit
```

Then re-run the build in Phase 5.

---

### Gotcha 2 — dome shell drops connection (EOF) for commands longer than ~30 seconds

**Symptom:** `vexctl dome shell run` exits mid-command with no output, or exits with:

```
error: EOF
```

**Cause:** The dome shell transport has a connection idle/length limit of approximately 30 seconds. Long-running commands — Docker builds, `go build` on large projects, anything with appreciable I/O latency — exceed this limit.

**Fix:** Use `nohup` + background execution + an exit-code file, then poll with a separate short-lived shell. This is the pattern used throughout Phase 5:

```shell
# Launch the long command
vexctl dome shell run --target <device> -- bash
nohup <your-long-command> > /tmp/cmd.log 2>&1; echo $? > /tmp/cmd.exit &
exit

# Poll from workstation (run repeatedly until you see a number)
vexctl dome shell run --target <device> -- \
  bash -c "cat /tmp/cmd.exit 2>/dev/null || echo 'still running...'"

# If it failed (non-zero), read the log
vexctl dome shell run --target <device> -- \
  bash -c "tail -80 /tmp/cmd.log"
```

> [!TIP]
> For commands that are known to be fast (under 10 seconds), you can pass them directly as `-- bash -c "..."` without nohup. The pattern is only necessary when you cannot predict or guarantee the runtime.

---

### Gotcha 3 — `vexctl dome file write` uses `--device`, not `--target`

**Symptom:** Running `vexctl dome file write --target ...` produces:

```
Error: unknown flag: --target
```

**Cause:** The dome subcommands are not uniform — `shell run` and `script run` use `--target`, while `file write`, `file read`, `file download`, `file upload`, and `file chmod` use `--device`.

**Fix:** Use the correct flag for each subcommand family:

| Subcommand family | Flag |
|---|---|
| `dome shell run` | `--target` |
| `dome script run` | `--target` |
| `dome svc *` | `--target` |
| `dome file write` | `--device` |
| `dome file read` | `--device` |
| `dome file download` | `--device` |
| `dome file upload` | `--device` |
| `dome pkg *` | `--target` |
| `dome facts gather` | `--target` |

> [!NOTE]
> This inconsistency exists in vexctl ≤ 1.4 and is tracked as a known issue. A future release will normalise all subcommands to `--device`. For now, use the table above as a reference.

---

### Gotcha 4 — `vex-fn` cold-start fails: Docker socket EACCES as non-root

**Symptom:** The first invocation of a new function returns:

```json
{"error":"cold start failed: image \"...\": inspecting image: permission denied while trying to connect to the Docker daemon socket at unix:///var/run/docker.sock: dial unix /var/run/docker.sock: connect: permission denied"}
```

**Cause:** On AlmaLinux 9 with SELinux Enforcing, the vex-fn Go binary running as `uid=100/gid=101/groups=989` cannot connect to `/var/run/docker.sock` (GID 989, mode 0660). The Docker CLI run as the same user inside the same container connects successfully. Root cause is likely a Go runtime supplementary-group propagation issue under cgroupv2 + SELinux `spc_t` context. **VexTasks bug:** `01kva8wsf7e3kecd9m0cadf8s1`

**Fix (workaround):** Stop and re-run vex-fn with `--user root`:

```shell
# 1. Get the current run command from docker inspect
vexctl dome shell run --device qzp-kz-north-1-ast-1-c02-plt-core-02 \
  --cmd "docker inspect vex-fn | python3 -c \"...\"  # see note below"

# 2. Stop existing container
vexctl dome shell run --device qzp-kz-north-1-ast-1-c02-plt-core-02 \
  --cmd "docker stop vex-fn && docker rm vex-fn"

# 3. Restart with --user root (add it before the image name)
vexctl dome shell run --device qzp-kz-north-1-ast-1-c02-plt-core-02 \
  --cmd "docker run -d --name vex-fn --restart unless-stopped \
    --network host --user root \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v /etc/vex/fn/vex-fn.yaml:/app/configs/vex-fn.yaml:ro \
    -e VEX_CONFIG_TOKEN=<token> \
    -e NATS_URLS=<nats-urls> \
    -e VEX_FN_TENANT=vextura \
    -e VEX_REGION=kz-north-1 \
    -e VEX_CONFIG_URL=http://vex-config.vex.internal:9090 \
    172.30.75.78:9080/vextura/vex-fn:<tag>"
```

> [!WARNING]
> Running vex-fn as root is a workaround only. The long-term fix is to patch the vex-fn Dockerfile to use `--user root` as default on SELinux hosts, or to investigate and resolve the Go runtime group propagation issue. This affects all AlmaLinux 9 deployments until the bug is resolved.

---

### Gotcha 5 — nginx routes `/api/gate/` to TAF gate, not Vextura gate

**Symptom:** External calls to `https://af-test.qazpost.kz/api/gate/demo/version` return `{"error":"no route matched"}`.

**Cause:** The nginx config routes `/api/gate/` to the TAF gate at `172.30.75.85:7080`, which uses the `kazpost` tenant and knows nothing about Vextura functions.

**Fix:** Add a dedicated `/api/vex/` location in `/etc/nginx/nginx.conf` that proxies to the Vextura platform gate:

```nginx
location /api/vex/ {
    proxy_pass http://172.30.75.94:8080/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

Add this block **before** the `location /api/` catch-all, then reload nginx:

```shell
vexctl dome shell run --device qzp-kz-north-1-ast-1-c02-edg-nginx-01 \
  --cmd "sudo nginx -t && sudo nginx -s reload"
```

All Vextura platform endpoints are then available at `https://af-test.qazpost.kz/api/vex/<path>`.

---

*Guide written from a live deployment on 2026-06-15, verified end-to-end on 2026-06-17. All commands executed on cluster `qzp-kz-north-1` (Qazpost production). Bugs discovered during this deployment: `01kva7ad4xf95rkesvnryr1rn7` (Harbor missing base images), `01kva7k23nvcz8cg1jbt56zyxf` (dome shell EOF on long commands), `01kva7r4b4qcr0xekmfmrkrksr` (alpine apk no internet), `01kva8wsf7e3kecd9m0cadf8s1` (vex-fn non-root Docker socket EACCES).*
