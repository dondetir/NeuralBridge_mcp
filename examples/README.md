# NeuralBridge Examples

This directory contains demos, tests, and documentation for NeuralBridge.

---

## 📁 Structure

```
examples/
├── demos/                   # Demo scripts
│   ├── mcp_client.py       # MCP client library
│   ├── demo_android_version.py
│   └── demo_streamhub.py
│
├── tests/                   # Test scripts
│   ├── test_connection.py  # TCP connection test
│   └── verify_phase1.sh    # Full system verification
│
└── docs/                    # Documentation
    ├── DEMO_SUMMARY.md     # Complete demo walkthrough
    └── manual_test_results.md
```

---

## 🚀 Quick Start

### Run Demos

```bash
# Demo 1: Get Android version
python3 demos/demo_android_version.py

# Demo 2: Navigate streamHub app
python3 demos/demo_streamhub.py
```

### Run Tests

```bash
# Verify entire system
bash tests/verify_phase1.sh

# Test TCP connection only
python3 tests/test_connection.py
```

---

See full documentation at [docs/DEMO_SUMMARY.md](docs/DEMO_SUMMARY.md)
