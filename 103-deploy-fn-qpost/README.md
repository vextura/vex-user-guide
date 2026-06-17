# 103 — Deploy a Serverless Function

> This guide walks the complete path from a blank directory to a live, JWT-protected HTTP endpoint backed by a serverless function. It covers two flows:
>
> - **Local dev** — build and test against your local Vextura stack
> - **Remote cluster** — deploy to any registered cluster (air-gapped or internet-connected)
>
> Every command uses `vexctl`. No raw `curl`, no hardcoded IPs, no admin keys in your terminal. Cluster-specific values (registry, region, cluster ID) are resolved at runtime — the same Smithy and `vex.yaml` work across all clusters.

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

## Flow B — Remote Cluster (Air-Gapped)

Use this flow to deploy to any remote Vextura cluster. If the cluster is air-gapped (no outbound Docker Hub access), follow the base image bootstrap step — otherwise skip it.

### B0 — Resolve cluster variables

Every command in this flow uses four values. Look them up once and export them as shell variables — never hardcode them.

```shell
# List registered clusters and pick yours
vexctl cluster list

# Export the four variables for your cluster
export CLUSTER=<cluster-id>            # e.g. qzp, beta, prod
export REGION=$(vexctl cluster get $CLUSTER --field region)
export REGISTRY=$(vexctl cluster get $CLUSTER --field registry)
export TENANT=vextura                  # or your tenant slug
```

> If `vexctl cluster get` is not available, look up values from the cluster profile:
> ```shell
> vexctl cluster list --output json | jq '.[] | select(.id == "'$CLUSTER'") | {region, registry}'
> ```

Once set, copy these into `vex.yaml` so you never pass flags again:

```yaml
# hello-fn/vex.yaml
smithy: smithy/hello.smithy
tenant: vextura
registry: <REGISTRY>/<TENANT>   # e.g. 172.30.75.78:9080/vextura
tag: v1.0.0
```

With these in `vex.yaml`, all commands below work with zero flags (except `--cluster`).

### B1 — Bootstrap base images (one-time, air-gapped clusters only)

Skip this step if the cluster can pull from Docker Hub directly.

The Dockerfile needs `golang:1.25-alpine` (builder) and `alpine:3.19` (runtime). Find a node on the cluster that has internet access, open a dome shell, and push the images to the cluster registry.

```shell
# Find a node with internet access — typically the bastion or edge node
vexctl dome facts gather --target $CLUSTER | grep -i internet

# Open a dome shell on that node
vexctl dome shell run --target <device-id>

# Inside the dome shell — push base images to the cluster registry
docker login $REGISTRY --username admin
docker pull golang:1.25-alpine
docker tag  golang:1.25-alpine $REGISTRY/library/golang:1.25-alpine
docker push $REGISTRY/library/golang:1.25-alpine

docker pull alpine:3.19
docker tag  alpine:3.19 $REGISTRY/library/alpine:3.19
docker push $REGISTRY/library/alpine:3.19

exit
```

> **One-time only.** Once `golang:1.25-alpine` and `alpine:3.19` are in the cluster registry, skip this step for all future deployments of any function.

### B2 — Generate stubs for air-gapped build

On your workstation, regenerate so the Dockerfile pulls from the cluster registry instead of Docker Hub:

```shell
vexctl fn generate --base-registry $REGISTRY/library
```

This rewrites the `FROM` lines in the generated `Dockerfile` to use `$REGISTRY/library/golang:1.25-alpine` etc. Your `handler.go` is not touched.

> **Internet-connected cluster:** skip this step and use `vexctl fn generate` without `--base-registry`. The Dockerfile will pull directly from Docker Hub during build.

### B3 — Build & push

```shell
vexctl fn build --push
```

If `registry` and `tag` are set in `vex.yaml`, no flags needed. Otherwise:

```shell
vexctl fn build --registry $REGISTRY/$TENANT --tag v1.0.0 --push
```

`vexctl` constructs `registry/fn-name:tag` and runs `docker build` then `docker push`. The Smithy is never touched.

> **Cross-platform:** Apple Silicon (arm64) workstation targeting an amd64 cluster — add `--platform linux/amd64`. `vexctl` switches to `docker buildx build` automatically.

### B4 — Register the fn manifest

```shell
vexctl fn publish --cluster $CLUSTER
```

Or with explicit flags if not set in `vex.yaml`:

```shell
vexctl fn publish --registry $REGISTRY/$TENANT --tag v1.0.0 --cluster $CLUSTER
```

Derives the image as `registry/hello-fn:tag` and pushes the fn manifest to `vex-config` on the target cluster. Never reads the `image:` field from Smithy.

### B5 — Deploy routes

```shell
vexctl gate deploy --cluster $CLUSTER
```

Merges the route table into `vex-config` on the target cluster. Safe to re-run — never wipes routes from other services.

Wait 30 seconds for the gate to pick up the new routes.

### B6 — Test

```shell
GATE=$(vexctl rip resolve vex-gate $REGION)
TOKEN=$(vexctl auth token --cluster $CLUSTER)

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
docker pull $REGISTRY/$TENANT/hello-fn:v1.0.0
```

**Routes wiped after `gate deploy`**

You ran `gate deploy --replace`. Without `--replace`, deploy always merges. Restore from source of truth:
```shell
bash scripts/seed-control-plane-routes.sh
```
