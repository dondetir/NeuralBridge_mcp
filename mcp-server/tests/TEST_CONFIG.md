# Integration Test Configuration

## Test Device Information

- **Device ID:** `emulator-5554`
- **Android API Level:** 35 (Android 15)
- **Architecture:** x86_64
- **Emulator Type:** Android TV

## Companion App Status

- **Package:** `com.neuralbridge.companion`
- **Installation:** ✅ Installed
- **AccessibilityService:** ✅ Enabled
  - Service: `com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService`
- **TCP Server:** ✅ Listening on port 38472

## ADB Configuration

- **ADB Path:** `~/Android/Sdk/platform-tools/adb`
- **Port Forwarding:** ✅ Active
  - Forward rule: `tcp:38472 → tcp:38472`

## Test Execution

### Run All Integration Tests
```bash
cd mcp-server
cargo test --test integration_tests
```

### Run with Custom Device
```bash
TEST_DEVICE_ID=emulator-5556 cargo test --test integration_tests
```

### Run in CI Environment
```bash
RUN_INTEGRATION_TESTS=1 cargo test --test integration_tests
```

## Test Coverage

✅ **test_device_connection** - Verifies TCP connection to companion app
✅ **test_port_forwarding_active** - Validates ADB port forwarding setup
✅ **test_companion_app_installed** - Confirms companion APK is installed
✅ **test_accessibility_service_enabled** - Checks AccessibilityService status

## Setup Verification Checklist

- [x] Android emulator running
- [x] Companion APK installed
- [x] AccessibilityService enabled
- [x] ADB port forwarding configured
- [x] TCP connection successful
- [x] Integration test scaffold created

## Next Steps (Phase 1 Week 3)

1. Add protobuf message tests (send/receive validation)
2. Add gesture execution tests
3. Add screenshot capture tests
4. Add UI tree extraction tests
5. Add performance benchmarks (<100ms latency target)

## Troubleshooting

### Connection Timeout
If tests fail with "Connection timeout":
1. Check emulator is running: `adb devices`
2. Verify companion app is running: `adb shell ps | grep neuralbridge`
3. Check port forwarding: `adb forward --list`
4. Verify TCP server: `adb shell netstat -an | grep 38472`

### AccessibilityService Not Enabled
```bash
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

### Port Forwarding Not Set Up
```bash
adb forward tcp:38472 tcp:38472
```
