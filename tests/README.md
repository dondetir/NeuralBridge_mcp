# NeuralBridge Tests

Testing strategy and test suites for the NeuralBridge automation platform.

## Test Structure

```
tests/
├── integration/        # Integration tests (Rust + Android)
├── fixtures/           # Test data and mock responses
└── README.md          # This file
```

## Testing Strategy

### Unit Tests

**Rust (MCP Server):**
```bash
cd mcp-server
cargo test
```

Tests included:
- Protocol codec (header encoding/decoding)
- Message framing (MessageFramer)
- ADB command parsing
- Device discovery output parsing
- Selector matching logic
- Semantic type classification

**Android (Companion App):**
```bash
cd companion-app
./gradlew test
```

Tests included:
- UI tree element extraction
- Stable element ID generation
- Gesture path construction
- Protocol message encoding/decoding

### Integration Tests

Integration tests require:
- Running Android emulator or device
- Companion app installed and AccessibilityService enabled
- Port forwarding set up

**Run Integration Tests:**
```bash
cd tests/integration
cargo test --test end_to_end
```

Test scenarios:
- MCP server connects to companion app
- UI tree retrieval
- Tap gesture execution
- Screenshot capture
- Element finding

### E2E Tests

End-to-end tests validate complete workflows:
- Launch app → Find element → Tap → Verify result
- Input text → Submit → Wait for response
- Take screenshot → Verify dimensions

**Prerequisites:**
- Test APK installed on device
- Known UI states for verification

**Run E2E Tests:**
```bash
# TODO Week 6: Implement E2E test runner
```

## CI/CD Testing

### Automated Testing in CI

GitHub Actions workflow (`.github/workflows/test.yml`):
- Rust unit tests on every commit
- Android unit tests on every commit
- Integration tests on main branch
- E2E tests with emulator (nightly)

**Setup:**
```yaml
# TODO Week 6: Create CI workflow
```

### Emulator Setup for CI

Docker image with Android emulator:
```bash
# TODO Week 6: Create Docker image
docker build -t neuralbridge-test-env .
docker run --privileged -p 5555:5555 neuralbridge-test-env
```

## Test Data

### Fixtures

- `fixtures/ui_trees/` - Sample UI tree JSON for testing element resolution
- `fixtures/screenshots/` - Test images for screenshot verification
- `fixtures/protobuf/` - Binary protobuf message samples

### Mock Responses

Mock companion app responses for testing MCP server without real device:
- `fixtures/mocks/tap_response.bin`
- `fixtures/mocks/ui_tree_response.bin`
- `fixtures/mocks/screenshot_response.bin`

## Performance Testing

### Latency Measurement

Test tools for measuring operation latencies:
```bash
# TODO Week 6: Implement latency measurement tool
cargo run --bin latency_test -- --iterations 100
```

Target metrics:
- Tap: <30ms
- UI tree (<500 nodes): <50ms
- Screenshot (1080p): <60ms

### Load Testing

Simulate multiple concurrent tool calls:
```bash
# TODO Week 6: Implement load testing
cargo run --bin load_test -- --clients 10 --duration 60s
```

## Manual Testing

### Test Checklist

Before releases, manually verify:

**Setup:**
- [ ] App installs on Android 7-15
- [ ] AccessibilityService enables correctly
- [ ] Port forwarding works
- [ ] MCP server connects

**Observe Tools:**
- [ ] UI tree retrieval works
- [ ] Screenshot captures correctly
- [ ] Element finding matches expected
- [ ] Foreground app detection accurate

**Act Tools:**
- [ ] Tap gestures execute at correct location
- [ ] Swipe gestures scroll content
- [ ] Text input enters text correctly
- [ ] Global actions work (back, home, recents)

**Manage Tools:**
- [ ] App launch opens correct app
- [ ] App close terminates app
- [ ] URL opening navigates to browser

**Wait Tools:**
- [ ] Wait for element succeeds when element appears
- [ ] Wait for gone succeeds when element disappears
- [ ] Wait for idle detects UI stability

### Test Apps

Recommended apps for testing:
- **Chrome** - Web browsing, text input
- **Settings** - System navigation, scrolling lists
- **Calculator** - Simple taps, verification
- **Gmail** - Complex UI, notifications

## Debugging Tests

### View Test Logs

```bash
# Rust tests with output
RUST_LOG=debug cargo test -- --nocapture

# Android tests
adb logcat -s TestRunner:V
```

### Debug Integration Tests

Use breakpoints in VS Code:
1. Add breakpoint in test code
2. Run "Debug Tests" configuration
3. Step through MCP server ↔ companion app communication

## Known Issues

### Flaky Tests

Some tests may be flaky due to:
- Race conditions in UI updates
- Device performance variations
- Network timing

**Mitigation:**
- Use proper wait conditions
- Increase timeouts for slow devices
- Retry failed tests (max 3 times)

### Emulator Limitations

Android emulator limitations:
- Slower gesture execution
- Less accurate touch coordinates
- Different performance characteristics

**Recommendation:** Test on real devices for accurate results.

## See Also

- [MCP Server Tests](../mcp-server/tests/) - Rust unit tests
- [Companion App Tests](../companion-app/app/src/test/) - Android unit tests
