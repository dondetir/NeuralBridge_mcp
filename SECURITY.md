# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.x     | Yes       |

## Reporting a Vulnerability

If you discover a security vulnerability in NeuralBridge, please report it responsibly.

**Do not open a public issue.**

### How to Report

Use [GitHub's private vulnerability reporting](https://github.com/dondetir/NeuralBridge_mcp/security/advisories/new) to submit your report.

### What to Include

- Description of the vulnerability
- Steps to reproduce
- Affected component (MCP server, companion app, protocol)
- Impact assessment (what an attacker could achieve)
- Any suggested fix, if you have one

### Response Timeline

- **Acknowledgment:** Within 48 hours
- **Initial assessment:** Within 7 days
- **Fix or mitigation:** Within 90 days (depending on severity)

We will coordinate disclosure with you. Credit will be given in the release notes unless you prefer to remain anonymous.

## Scope

### In Scope

- Unauthorized access to the MCP HTTP server from the local network
- Remote code execution via MCP tool calls
- Privilege escalation through the AccessibilityService
- Data exfiltration (UI tree data, screenshots, clipboard)
- Protocol vulnerabilities (JSON-RPC parsing, HTTP handling)
- Denial of service against the MCP server or companion app

### Out of Scope

- Attacks requiring physical access to the device
- Attacks requiring a rooted/jailbroken device
- Attacks requiring the user to install a malicious app alongside NeuralBridge
- Social engineering
- Vulnerabilities in dependencies (report these to the upstream project)

## Security Architecture

### Network exposure

NeuralBridge runs an **MCP HTTP server (Ktor CIO) on port 7474, bound to 0.0.0.0** (all network interfaces). This is network-facing by design — the AI agent connects over WiFi from another machine on the same local network. There is **no TLS**; all traffic is plaintext HTTP.

A legacy TCP/protobuf server on port 38472 is still present but binds to **localhost only** and is not used by MCP clients.

### Authentication

An API key infrastructure exists (`McpAuthManager.kt` — generates and stores a per-device UUID key), but it is **not currently enforced** on incoming HTTP requests. Any device on the same network can call MCP tools without authentication.

### CORS

The `/mcp` endpoint returns `Access-Control-Allow-Origin: *`, allowing requests from any browser origin.

### AccessibilityService

The companion app uses Android's AccessibilityService API, which grants full UI control (taps, swipes, text input, reading the UI tree). This requires explicit user enablement in device Settings. MediaProjection (for screenshots) requires a separate user consent dialog.

### Known risks

- **No auth enforcement:** Anyone on the same WiFi network can invoke all 32 MCP tools, including gestures, text input, and screenshot capture.
- **No encryption:** HTTP traffic (including screenshots and UI tree data) is transmitted in plaintext.
- **Full device control:** The AccessibilityService can perform any UI action a human user can. Combined with the lack of auth, this means any local network attacker has full device control.
- **CORS wildcard:** Browser-based attacks from any origin can reach the server if the attacker knows the device IP.

These are accepted trade-offs for a development/research tool. Do not run NeuralBridge on untrusted networks.
