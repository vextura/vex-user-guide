# Run a dome operation — Go SDK

Call vex-dome (the device operations engine) from a client using the generated Go SDK.

Verified against qzp-kz-north-1 on 2026-07-08 by shelling into the Qazpost bastion (`10.200.1.2`) and running this exact example against `vex-gate` at `172.30.75.94:8080`.

## What it does

Uses the [`vex-dome-sdk-go`](https://github.com/vextura/vex-dome-sdk-go) client to hit a target device four ways in a row:

1. `ShellRun` — one-off command (`hostname && uptime`)
2. `FactsGather` — snapshot the OS (kernel, arch, cpu, mem, uptime, package manager, init system)
3. `ShellBatch` — three commands with per-step results
4. `SvcStatus(sshd)` — check a systemd unit

Every op accepts either `DeviceId` (looked up in vex-inventory — must be `operational`) or an explicit `Target` block (host / port / user / key_id).

## Prerequisites

- Go 1.21+
- Network reach to the cluster's `vex-gate` — run from bastion, or open an SSH tunnel
- Client credentials for the cluster's `vex-auth` (admin creds or an M2M service account)
- The target `DEVICE_ID` must exist in `vex-inventory` and be in `status=operational`

## Install the SDK

The SDK is a private GitHub module. Configure Go to fetch it directly (bypass the public proxy):

```bash
go env -w GOPRIVATE=github.com/vextura/*
```

If your GitHub PAT isn't already in Git credentials, add:

```bash
git config --global url."https://<GITHUB_TOKEN>@github.com/".insteadOf "https://github.com/"
```

## Run the example

```bash
cd 104-run-dome-op-go

export VEX_GATE_URL=http://172.30.75.94:8080
export VEX_AUTH_URL=http://172.30.75.94:8080
export VEX_CLIENT_ID=admin
export VEX_CLIENT_SECRET=<qzp-admin-secret>
export DEVICE_ID=qzp-kz-north-1-ast-1-c02-plt-core-01

go run .
```

Expected output:

```
=== vex-dome smoketest against qzp-kz-north-1-ast-1-c02-plt-core-01 ===

--- ShellRun ---
ok         : true
exit_code  : 0
duration_ms: 147
stdout     : baraiq-p-dbpr02.kazpost.kz
 23:05:06 up 169 days, 12:11,  0 users,  load average: 0.15, 0.15, 0.17

--- FactsGather ---
os         : AlmaLinux 9.3 (5.14.0-…)
arch       : x86_64
hostname   : baraiq-p-dbpr02.kazpost.kz
cpu / mem  : 8 cores / 32000 MB
uptime     : 14638260 seconds
pkg / init : dnf / systemd

--- ShellBatch (3 commands) ---
  $ whoami                    → exit=0 ok=true: khassangali
  $ date -u                   → exit=0 ok=true: Tue Jul  8 06:05:07 UTC 2026
  $ df -h / | tail -1         → exit=0 ok=true: /dev/mapper/… 45G 30G 15G 67% /

--- SvcStatus(sshd) ---
active=true enabled=true exit=0
```

## Try it against another cluster

The example is cluster-agnostic — swap the four env vars.

| Cluster | `VEX_GATE_URL` | `DEVICE_ID` (sample) |
|---|---|---|
| dev (local) | `http://localhost:8080` | any device seeded in local `vex-inventory` (e.g. `qzp-kz-north-1-ast-1-c02-ops-bsn-01`) |
| prod (kz-north-1) | via bastion tunnel or `10.200.1.7:8080` | `vex-kz-north-1-ast-c01-plt-core-02` |
| qzp | `http://172.30.75.94:8080` | `qzp-kz-north-1-ast-1-c02-plt-core-01` |

## Target block instead of DeviceId

If the device isn't registered in vex-inventory, pass an explicit `Target`:

```go
client.ShellRun(ctx, &dome.ShellRunInput{
    Target: &dome.TargetBlock{
        Host: "10.200.1.7", Port: 22, User: "khassangali",
        KeyId: "default",  // resolves to SSH key in vex-config
        Transport: "ssh",
    },
    Command: "uptime",
})
```

## Common errors

| Status | What to check |
|---|---|
| `401 Unauthorized` | JWT signed by wrong `vex-auth`. Rerun with the right cluster's `VEX_AUTH_URL` — client_credentials mint locally match the local vex-auth's signing key. |
| `403 Forbidden` | Client has valid JWT but no IAM role for the op. Check `admin.dome:*` binding in vex-iam. |
| `404 device not found` | `DEVICE_ID` isn't in vex-inventory, OR its status isn't `operational`. `PATCH /devices/{id}/status {"status":"operational"}` on the inventory endpoint. |
| `500 ssh: unable to authenticate` | The `key_id` referenced by the device (or `Target.KeyId`) doesn't resolve to an SSH private key. On qzp/prod the dome fn ships with `DOME_SSH_KEY_DEFAULT` baked into its env. |

## Smithy → RIP → SDK — the full pipeline

The SDK code is generated from `vex-dome/smithy/dome.smithy`. The chain that put it there:

```bash
# 1. Push smithy schema to vex-config on each cluster:
vexctl gate deploy --smithy vex-dome/smithy/dome.smithy \
  --tenant vextura --token $ADMIN_KEY \
  --config-url http://vex-config.vex.internal:9090 \
  --rip-region kz-north-1 --rip-endpoint http://vex-dome:8099

# 2. Link the schema_id into RIP so the generator can find it:
vexctl rip register --service vex-dome --region kz-north-1 \
  --endpoint http://vex-dome:8099 \
  --schema-id 63091a5e66c47104c3bce40d2ca5a9cdd86d52888e578526f32d05acb9c948e7 \
  --auth-flow m2m --token $ADMIN_KEY \
  --config-url http://vex-config.vex.internal:9090

# 3. Generate the SDK from the registered schema:
vexctl sdk generate --service vex-dome --tenant vextura \
  --lang go --module github.com/vextura/vex-dome-sdk-go \
  --output ../vex-dome-sdk-go/ \
  --config-url http://vex-config.vex.internal:9090
```

Same `schema_id` on dev / prod / qzp → the SDK is byte-for-byte identical across all three environments.
