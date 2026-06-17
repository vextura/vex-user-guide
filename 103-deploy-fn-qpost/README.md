# 103 — Deploy a Serverless Function

> This guide walks the complete path from a blank directory to a live, JWT-protected HTTP endpoint backed by a serverless function. It covers two flows:
>
> - **Local dev** — build and test against your local Vextura stack
> - **Remote cluster** — deploy to the Qazpost air-gapped cluster (`kz-north-1`)
>
> Every command uses `vexctl`. No raw `curl`, no hardcoded IPs, no admin keys in your terminal.

**What you will build:** A three-operation function (`hello-fn`) exposed through `vex-gate`:

| Route | Operation | Auth |
|---|---|---|
| `GET /demo/hello` | `HelloGreet` | JWT |
| `POST /demo/echo` | `HelloEcho` | JWT |
| `GET /demo/version` | `HelloVersion` | JWT |

**Time:** ~10 min local, ~30 min remote (most of it is the first Docker build on an air-gapped node).

---

## Prerequisites

| Tool | Check |
|---|---|
| `vexctl` ≥ 1.4 | `vexctl version` |
| `docker` | `docker info` |
| vexctl authenticated | `vexctl auth status` |
| Local stack running (local flow only) | `vexctl fn gate health` |

---

## Phase 1 — Project Layout

Create the project on your workstation:

```shell
mkdir -p hello-fn/smithy
cd hello-fn
```

Create `vex.yaml` at the project root — this is what makes all commands work with zero flags:

```yaml
# hello-fn/vex.yaml
smithy: smithy/hello.smithy
tenant: vextura
# registry: 172.30.75.78:9080/vextura   # uncomment for remote cluster
# tag: v1.0.0                            # uncomment to pin a version
```

> **Why `vex.yaml`?** Every `vexctl fn` and `vexctl gate` command reads this file automatically. You never pass `--smithy`, `--tenant`, `--registry`, or `--tag` again. The image reference (`registry/fn-name:tag`) is computed at build/publish time from these values — the Smithy never mentions a registry or tag.

---

## Phase 2 — Smithy Model

Write `smithy/hello.smithy`:

```smithy
$version: "2"

namespace vextura.hello

use vextura.platform#vexFn
use vextura.platform#vexGate
use vextura.platform#vexRoute

/// Demo greeting service — routes through vex-gate to hello-fn via NATS.
@vexGate(label: "Hello Demo", group: "Demo", description: "Greeting demo service", order: 99)
service HelloService {
    version: "1.0.0"
    operations: [HelloGreet, HelloEcho, HelloVersion]
}

// ─── HelloGreet ──────────────────────────────────────────────────────────────

/// Returns a greeting message.
@vexFn(name: "hello-fn", tenant: "vextura")
@vexRoute(path: "/demo/hello", method: "GET", auth: "jwt")
operation HelloGreet {
    output: HelloGreetOutput
}

structure HelloGreetOutput {
    message: String
    region:  String
    status:  String
}

// ─── HelloEcho ───────────────────────────────────────────────────────────────

/// Echoes the request body — useful for integration testing.
@vexFn(name: "hello-fn", tenant: "vextura")
@vexRoute(path: "/demo/echo", method: "POST", auth: "jwt")
operation HelloEcho {
    input:  HelloEchoInput
    output: HelloEchoOutput
}

structure HelloEchoInput  { body: Document }
structure HelloEchoOutput { body: Document }

// ─── HelloVersion ────────────────────────────────────────────────────────────

/// Returns service version metadata.
@vexFn(name: "hello-fn", tenant: "vextura")
@vexRoute(path: "/demo/version", method: "GET", auth: "jwt")
operation HelloVersion {
    output: HelloVersionOutput
}

structure HelloVersionOutput {
    version: String
    service: String
    tenant:  String
}
```

**Key rules in this Smithy:**

- `@vexRoute` — not `@http`. Routes are dispatched via NATS (`vexedge.vextura.fn.hello-fn`), not HTTP proxy.
- No `targetUrl` on `@vexGate` — only for HTTP-proxy services like `vex-iam`. For fn-backed services the gate resolves by fn name. Adding `targetUrl` breaks routing.
- **No `image:` field** — the image reference (`registry/hello-fn:tag`) is computed at publish time from `vex.yaml` + flags. The Smithy is environment-agnostic and never changes between local dev and production.
- Static paths only. VEXBLUE rule: static before parameterised.

---

## Phase 3 — Generate Handler Stubs

```shell
vexctl fn generate
```

Reads `vex.yaml` → `smithy/hello.smithy`. Generates under `functions/hello-fn/`:

| File | Behaviour |
|---|---|
| `main.go` | Wires `vexfn.Handle` — **regenerated every run** |
| `handler.go` | Your logic stub — **generated once, never overwritten** |
| `Dockerfile` | Multi-stage build (`golang:1.25-alpine` → `alpine:3.19`) — **regenerated every run** |
| `go.mod` | Module file with local SDK replace — **generated once, never overwritten** |

After generation, `go mod tidy` and `go mod vendor` run automatically. The `vendor/` directory is ready for an offline Docker build.

---

## Phase 4 — Implement `handler.go`

Replace the generated stub:

```go
// functions/hello-fn/handler.go
package main

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/vextura/vex-sdk-go/vexfn"
)

func Handle(ctx context.Context, evt vexfn.Event[json.RawMessage]) (*json.RawMessage, error) {
	switch evt.Operation {
	case "HelloGreet":
		return greet()
	case "HelloEcho":
		return echo(evt.Data)
	case "HelloVersion":
		return ver()
	default:
		return nil, fmt.Errorf("hello-fn: unknown operation %q", evt.Operation)
	}
}

func greet() (*json.RawMessage, error) {
	out, _ := json.Marshal(map[string]string{
		"message": "Hello from vex-fn!",
		"region":  "kz-north-1",
		"status":  "ok",
	})
	r := json.RawMessage(out)
	return &r, nil
}

func echo(body json.RawMessage) (*json.RawMessage, error) {
	if len(body) == 0 {
		e, _ := json.Marshal(map[string]any{})
		r := json.RawMessage(e)
		return &r, nil
	}
	return &body, nil
}

func ver() (*json.RawMessage, error) {
	out, _ := json.Marshal(map[string]string{
		"version": "1.0.0",
		"service": "hello-fn",
		"tenant":  "vextura",
	})
	r := json.RawMessage(out)
	return &r, nil
}
```

> **Transport note:** The fn transport is stdio — `vex-fn` attaches to the container's stdin/stdout and exchanges JSON. Never start an HTTP server inside a fn. It will compile, but every invocation will time out after 30 seconds.

Verify the build compiles before touching any cluster:

```shell
cd functions/hello-fn
go build -mod=vendor ./...
cd ../..
```

---

## Flow A — Local Dev

Use this flow to develop and test against your local Vextura stack (`docker-compose.dev.yml`).

### A1 — Build the image locally

```shell
vexctl fn build
```

Reads `vex.yaml`, parses the Smithy, runs `docker build` for `hello-fn`. No registry needed — the image lands locally as `hello-fn:latest`. `vex-fn` shares the Docker socket (`/var/run/docker.sock`) so it can start the container directly.

### A2 — Register the fn manifest

```shell
vexctl fn publish
```

No `--registry` needed in local mode. `vexctl` pushes the fn manifest (name, image, resource limits, mode) to `vex-config`. The image is already on the local Docker daemon — nothing is pushed to a registry.

### A3 — Deploy routes

```shell
vexctl gate deploy
```

Reads `vex.yaml` → Smithy → generates the route table → **merges** it into `vex-config`. Existing routes for other tenants or services are untouched.

> **Merge is the default.** You can run `gate deploy` safely at any time without wiping platform routes. Use `--replace` only when you want to completely replace the tenant's route table (destructive, use with care).

Wait ~30 seconds for `vex-gate` to pick up the new routes (it polls `vex-config` on a 30-second cycle).

### A4 — Test

Get a JWT:

```shell
TOKEN=$(vexctl auth token)
```

Call each route:

```shell
# Greet
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/demo/hello | jq .

# Echo
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"ping": "pong"}' \
     http://localhost:8080/demo/echo | jq .

# Version
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/demo/version | jq .
```

Expected responses:

```json
// /demo/hello
{"message": "Hello from vex-fn!", "region": "kz-north-1", "status": "ok"}

// /demo/echo
{"ping": "pong"}

// /demo/version
{"version": "1.0.0", "service": "hello-fn", "tenant": "vextura"}
```

---

## Flow B — Remote Cluster (Qazpost Air-Gapped)

Use this flow to deploy to the Qazpost production cluster (`kz-north-1`). The cluster's Docker nodes cannot pull from Docker Hub — all images must come from the cluster's Harbor registry.

**Cluster quick-reference** (resolved at runtime — do not hardcode):

| What | How to get it |
|---|---|
| Gate endpoint | `vexctl rip resolve vex-gate kz-north-1` |
| Registry | `172.30.75.78:9080` (Harbor) |
| Cluster ID | `qzp` (vexctl profile or `--cluster qzp`) |
| Tenant | `vextura` |

### B0 — Bootstrap Harbor base images (one-time)

The Dockerfile uses `golang:1.25-alpine` (builder) and `alpine:3.19` (runtime). These must be in Harbor before the first build. Run this once per base image tag — skip on subsequent deployments.

```shell
# Open a dome shell on a node with outbound internet access
vexctl dome shell run --target qzp-kz-north-1-ast-1-c02-plt-core-01

# Inside the dome shell:
docker login 172.30.75.78:9080 --username admin
docker pull golang:1.25-alpine
docker tag  golang:1.25-alpine 172.30.75.78:9080/library/golang:1.25-alpine
docker push 172.30.75.78:9080/library/golang:1.25-alpine

docker pull alpine:3.19
docker tag  alpine:3.19 172.30.75.78:9080/library/alpine:3.19
docker push 172.30.75.78:9080/library/alpine:3.19

exit
```

### B1 — Generate stubs with the air-gapped base registry

On your workstation, regenerate so the Dockerfile pulls from Harbor instead of Docker Hub:

```shell
vexctl fn generate --base-registry 172.30.75.78:9080/library
```

This rewrites the `FROM` lines in the generated `Dockerfile`:
- `FROM 172.30.75.78:9080/library/golang:1.25-alpine AS builder`
- `FROM 172.30.75.78:9080/library/alpine:3.19`

Your `handler.go` is not touched.

### B2 — Build & push to Harbor

```shell
vexctl fn build --registry 172.30.75.78:9080/vextura --tag v1.0.0 --push
```

`vexctl` constructs the image reference as `registry/fn-name:tag` — `172.30.75.78:9080/vextura/hello-fn:v1.0.0` — without touching the Smithy. The same Smithy works from local dev to production.

> **Cross-platform note:** Apple Silicon (arm64) workstation → amd64 cluster, add `--platform linux/amd64`:
>
> ```shell
> vexctl fn build --registry 172.30.75.78:9080/vextura --tag v1.0.0 --platform linux/amd64 --push
> ```

### B3 — Register the fn manifest

```shell
vexctl fn publish --registry 172.30.75.78:9080/vextura --tag v1.0.0 --cluster qzp
```

Derives the image as `172.30.75.78:9080/vextura/hello-fn:v1.0.0` and pushes the manifest to `vex-config` on the remote cluster. The Smithy is not read for the image — `registry/name:tag` is always computed from the flags and vex.yaml.

### B4 — Deploy routes

```shell
vexctl gate deploy --cluster qzp
```

Reads `vex.yaml`, generates the route table from the Smithy, **merges** it into `vex-config` on the remote cluster.

Wait 30 seconds for the gate to pick up the new routes.

### B5 — Test against the cluster gate

Resolve the gate endpoint:

```shell
GATE=$(vexctl rip resolve vex-gate kz-north-1)
TOKEN=$(vexctl auth token --cluster qzp)
```

```shell
curl -s -H "Authorization: Bearer $TOKEN" http://$GATE/demo/hello | jq .
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"ping": "pong"}' \
     http://$GATE/demo/echo | jq .
curl -s -H "Authorization: Bearer $TOKEN" http://$GATE/demo/version | jq .
```

---

## Iterating

| What changed | Commands to run |
|---|---|
| `handler.go` logic only | `vexctl fn build` → `vexctl fn publish` → wait 30s |
| Added a new operation | `vexctl fn generate` → edit `handler.go` → `vexctl fn build` → `vexctl fn publish` → `vexctl gate deploy` → wait 30s |
| Route paths / auth changed | `vexctl gate deploy` → wait 30s |
| Tag bump for production | `vexctl fn build --tag v1.x.x --push` → `vexctl fn publish --tag v1.x.x` → wait 30s |

### Re-generation is safe

`vexctl fn generate` can be run at any time:
- `main.go` and `Dockerfile` are always regenerated from the Smithy
- `handler.go` and `go.mod` are **never overwritten** — your implementation is preserved
- If `vendor/` already exists, vendoring is skipped — existing dependencies are left intact

### Gate deploy is safe

`vexctl gate deploy` merges routes by default. Re-running it on the same Smithy is idempotent — it updates the tenant's routes without touching any other tenant or platform service. The `--replace` flag does a full replacement and should be used deliberately.

---

## Troubleshooting

**404 on `/demo/hello`**

Gate hasn't picked up the routes yet. Wait 30 seconds and retry. If still 404:
```shell
vexctl gate routes list --tenant vextura   # verify routes are registered
vexctl fn gate health                      # verify gate is up
```

**401 Unauthorized**

Token expired. Refresh:
```shell
TOKEN=$(vexctl auth token)
```

**fn returns timeout (30s)**

Your handler started an HTTP server or blocked on a long operation without the context. Check `handler.go` — the fn transport is stdio, not HTTP.

**`docker build` fails on air-gapped node**

Base images missing from Harbor. Re-run Phase B0 for the missing tag.

**`vexctl fn publish` — image not found on vex-fn**

The image tag in the fn manifest doesn't match what's in the registry. Confirm the image pushed successfully:
```shell
docker pull 172.30.75.78:9080/vextura/hello-fn:v1.0.0
```

**Routes wiped after `gate deploy`**

You ran `gate deploy --replace`. Without `--replace`, deploy always merges. Restore from source of truth:
```shell
bash scripts/seed-control-plane-routes.sh
```
