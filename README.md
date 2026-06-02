# Vextura Platform — User Guide

Practical guides for building on the Vextura Platform. Each guide is self-contained and runnable.

## Guides

| # | Title | What you'll learn |
|---|-------|-------------------|
| [101](./101-execute-workflow-java/) | Execute a Workflow — Java SDK | Install the SDK, resolve endpoints via RIP, execute a workflow, poll for result |

> More guides coming: Python SDK, Go SDK, deploying a service, setting up a pipeline.

## Before you start

You need access to a running Vextura Platform cluster and the `vexctl` CLI.

**Resolve your cluster endpoint** — all guides use RIP for endpoint discovery, never hardcoded URLs:

```bash
# Install vexctl (if not already installed)
# See: https://github.com/vextura/vexctl

# Verify you're authenticated
vexctl auth whoami

# Resolve the workflow engine endpoint for your region
vexctl rip resolve uwf-engine <your-region>
```

---

Questions or issues? Open a GitHub issue on this repo.
