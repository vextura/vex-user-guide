// Minimal example: use the generated vex-dome-sdk-go against a Vextura cluster.
//
// Verified end-to-end 2026-07-08 against qzp-kz-north-1 by shelling into the
// bastion (10.200.1.2) — vex-gate reachable at http://172.30.75.94:8080.
//
// Env:
//
//	VEX_GATE_URL       required, base URL of the cluster's vex-gate
//	VEX_AUTH_URL       required, base URL of the cluster's vex-auth (usually same as gate)
//	VEX_CLIENT_ID      required, e.g. "admin"
//	VEX_CLIENT_SECRET  required, matching secret
//	DEVICE_ID          required, full device id from vex-inventory
//	                   (e.g. qzp-kz-north-1-ast-1-c02-plt-core-01)
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"time"

	dome "github.com/vextura/vex-dome-sdk-go"
	"github.com/vextura/vex-sdk-go/pkg/sdkrt"
)

func main() {
	gateURL := requireEnv("VEX_GATE_URL")
	authURL := requireEnv("VEX_AUTH_URL")
	clientID := requireEnv("VEX_CLIENT_ID")
	clientSecret := requireEnv("VEX_CLIENT_SECRET")
	deviceID := requireEnv("DEVICE_ID")

	// M2M client_credentials — refreshes automatically.
	auth := sdkrt.NewM2MAuth(authURL, clientID, clientSecret)
	client := dome.NewWithEndpoint(gateURL, auth)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	fmt.Printf("=== vex-dome smoketest against %s ===\n", deviceID)

	// 1. Shell run — the most common op.
	shell, err := client.ShellRun(ctx, &dome.ShellRunInput{
		DeviceId: deviceID,
		Command:  "hostname && uptime",
		Timeout:  10,
	})
	if err != nil {
		log.Fatalf("ShellRun: %v", err)
	}
	fmt.Printf("\n--- ShellRun ---\n")
	fmt.Printf("ok         : %v\n", shell.Ok)
	fmt.Printf("exit_code  : %d\n", shell.ExitCode)
	fmt.Printf("duration_ms: %d\n", shell.DurationMs)
	fmt.Printf("stdout     : %s", shell.Stdout)

	// 2. Facts — snapshot of the OS.
	facts, err := client.FactsGather(ctx, &dome.FactsGatherInput{DeviceId: deviceID})
	if err != nil {
		log.Fatalf("FactsGather: %v", err)
	}
	fmt.Printf("\n--- FactsGather ---\n")
	fmt.Printf("os         : %s (%s)\n", facts.OsPretty, facts.Kernel)
	fmt.Printf("arch       : %s\n", facts.Arch)
	fmt.Printf("hostname   : %s\n", facts.Hostname)
	fmt.Printf("cpu / mem  : %d cores / %d MB\n", facts.CpuCount, facts.MemoryMb)
	fmt.Printf("uptime     : %d seconds\n", facts.UptimeSeconds)
	fmt.Printf("pkg / init : %s / %s\n", facts.PackageManager, facts.InitSystem)

	// 3. Batch shell — multiple commands with per-step failure handling.
	batch, err := client.ShellBatch(ctx, &dome.ShellBatchInput{
		DeviceId: deviceID,
		Commands: dome.StringList{"whoami", "date -u", "df -h / | tail -1"},
		Timeout:  10,
	})
	if err != nil {
		log.Fatalf("ShellBatch: %v", err)
	}
	fmt.Printf("\n--- ShellBatch (%d commands) ---\n", len(batch.Results))
	for _, r := range batch.Results {
		fmt.Printf("  $ %-25s → exit=%d ok=%v: %s", r.Command, r.ExitCode, r.Ok, r.Stdout)
	}

	// 4. Service status (systemd) — read-only, safe on any operational device.
	svc, err := client.SvcStatus(ctx, &dome.SvcInput{DeviceId: deviceID, Name: "sshd"})
	if err != nil {
		log.Fatalf("SvcStatus: %v", err)
	}
	fmt.Printf("\n--- SvcStatus(sshd) ---\n")
	fmt.Printf("active=%v enabled=%v exit=%d\n", svc.Active, svc.Enabled, svc.ExitCode)
}

func requireEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		log.Fatalf("ERROR: %s is required", key)
	}
	return v
}
