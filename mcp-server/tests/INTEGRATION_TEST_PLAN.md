# Phase 1 Integration Test Plan

**Status:** DRAFT - Waiting for Tasks #4 and #8 completion
**Target:** 30+ integration tests covering all 16 Phase 1 MCP tools
**Test Environment:** emulator-5554, Test Harness App (com.neuralbridge.testapp)

## Test Infrastructure

### Setup (Before Each Test)
- ✅ Verify emulator is running (adb devices)
- ✅ Verify companion app is installed and AccessibilityService enabled
- ✅ Verify TCP server is listening on port 38472
- ✅ Verify ADB port forwarding is active
- ✅ Establish MCP server connection to device
- ✅ Launch test harness app to known state

### Teardown (After Each Test)
- Reset test app state (force-stop + clear data)
- Close any opened apps
- Return to home screen

### Test Fixtures
- Test Harness App: com.neuralbridge.testapp (installed in Task #6)
- System Apps: Settings, Browser
- Known UI elements with stable resource IDs

---

## Test Suite (30+ Scenarios)

### 1. Connection Tests (3 tests)

#### TEST_001: MCP Server Connects to Companion App
**Setup:** Companion app running, port forwarding active
**Action:** Establish TCP connection via DeviceConnection::connect()
**Expected:** Connection successful, TCP_NODELAY set
**Verify:** is_alive() returns true

#### TEST_002: Connection Survives Device Sleep/Wake
**Setup:** Established connection
**Action:** Simulate device sleep (adb shell input keyevent POWER) → wait 2s → wake
**Expected:** Connection remains alive or auto-reconnects
**Verify:** Send request after wake, receives valid response

#### TEST_003: Auto-Reconnect After Companion Restart
**Setup:** Established connection
**Action:** Force-stop companion app → wait 3s → relaunch
**Expected:** Connection detects failure and reconnects
**Verify:** Next request succeeds without manual intervention

---

### 2. Observe Tests (8 tests)

#### TEST_004: get_ui_tree Returns Valid Hierarchy
**Setup:** Test harness app on Login screen
**Action:** Call get_ui_tree()
**Expected:** JSON tree with nodes, bounds, text, resource_id fields
**Verify:** Root node exists, username/password elements found

#### TEST_005: screenshot Returns Valid JPEG
**Setup:** Test harness app on Login screen
**Action:** Call screenshot(quality="full")
**Expected:** Base64-encoded JPEG data, size ~50KB
**Verify:** Decode to image, dimensions match device, validate JPEG header

#### TEST_006: screenshot Thumbnail Mode Works
**Setup:** Test harness app on Login screen
**Action:** Call screenshot(quality="thumbnail")
**Expected:** Base64-encoded JPEG, size ~20KB
**Verify:** Smaller than full quality, still valid JPEG

#### TEST_007: find_elements by Text Works
**Setup:** Test harness app on Login screen
**Action:** Call find_elements(text="Login")
**Expected:** Returns button element with matching text
**Verify:** Element has correct bounds, resource_id=button_login

#### TEST_008: find_elements by Resource ID Works
**Setup:** Test harness app on Login screen
**Action:** Call find_elements(resource_id="username")
**Expected:** Returns EditText element
**Verify:** Element has correct bounds, className="EditText"

#### TEST_009: find_elements Returns Multiple Matches
**Setup:** Test harness app on List screen
**Action:** Call find_elements(text="Item")
**Expected:** Returns array of 20 elements (Item 1-20)
**Verify:** All elements have item_text resource_id

#### TEST_010: get_foreground_app Returns Correct Package
**Setup:** Test harness app running
**Action:** Call get_foreground_app()
**Expected:** Returns "com.neuralbridge.testapp"
**Verify:** Package name matches

#### TEST_011: Selector Resolution Accuracy
**Setup:** Test harness app on Form screen
**Action:** Call find_elements with various selectors (text, id, combined)
**Expected:** Correct elements found for each selector type
**Verify:** checkbox_terms, radio buttons, date_input all resolvable

---

### 3. Gesture Tests (8 tests)

#### TEST_012: tap by Coordinates Works
**Setup:** Test harness app on Login screen
**Action:** get_ui_tree() → extract button_login bounds → tap(x, y)
**Expected:** Button click registered, navigation to List screen
**Verify:** get_foreground_app() returns ListActivity

#### TEST_013: tap by Text Selector Works
**Setup:** Test harness app on Login screen
**Action:** tap(selector={text="Login"})
**Expected:** Selector resolves to button, click registered
**Verify:** Navigation to List screen occurs

#### TEST_014: tap by Resource ID Selector Works
**Setup:** Test harness app on Login screen
**Action:** tap(selector={resource_id="button_login"})
**Expected:** Selector resolves to button, click registered
**Verify:** Navigation to List screen occurs

#### TEST_015: long_press Shows Context Menu
**Setup:** Test harness app on Login screen, username field
**Action:** long_press(selector={resource_id="username"})
**Expected:** EditText context menu appears (Paste, etc.)
**Verify:** get_ui_tree() shows context menu nodes

#### TEST_016: swipe Vertical Scrolls
**Setup:** Test harness app on List screen
**Action:** swipe(start_x, start_y, end_x, end_y, duration=500) downward
**Expected:** RecyclerView scrolls, items 15-20 become visible
**Verify:** find_elements(text="Item 20") returns element

#### TEST_017: press_key BACK Navigates
**Setup:** Test harness app on List screen
**Action:** press_key(key="BACK")
**Expected:** Navigation back to Login screen
**Verify:** get_foreground_app() returns LoginActivity

#### TEST_018: press_key ENTER Submits
**Setup:** Test harness app on Login screen, username focused
**Action:** input_text("testuser") → press_key(key="ENTER")
**Expected:** Focus moves to password field
**Verify:** get_ui_tree() shows password field focused

#### TEST_019: global_action HOME Works
**Setup:** Test harness app on any screen
**Action:** global_action(action="HOME")
**Expected:** Device returns to home screen
**Verify:** get_foreground_app() returns launcher package

---

### 4. Input Tests (3 tests)

#### TEST_020: input_text into Focused Field
**Setup:** Test harness app on Login screen, username focused
**Action:** input_text(text="testuser123")
**Expected:** Text appears in username field
**Verify:** get_ui_tree() shows text in username element

#### TEST_021: input_text with Selector Finds Field
**Setup:** Test harness app on Login screen
**Action:** input_text(text="testuser", selector={resource_id="username"})
**Expected:** Selector resolves to username field, text entered
**Verify:** get_ui_tree() shows text in username element

#### TEST_022: input_text Append Mode Works
**Setup:** Test harness app on Login screen, "test" already in username
**Action:** input_text(text="user", append=true)
**Expected:** Text appends to existing, becomes "testuser"
**Verify:** get_ui_tree() shows "testuser" in username

---

### 5. App Management Tests (3 tests)

#### TEST_023: launch_app Starts Settings
**Setup:** Test harness app running
**Action:** launch_app(package="com.android.settings")
**Expected:** Settings app opens
**Verify:** get_foreground_app() returns "com.android.settings"

#### TEST_024: close_app Force-Stops App
**Setup:** Test harness app running
**Action:** close_app(package="com.neuralbridge.testapp")
**Expected:** App is force-stopped
**Verify:** get_foreground_app() != "com.neuralbridge.testapp"

#### TEST_025: open_url Opens Browser
**Setup:** Home screen
**Action:** open_url(url="https://example.com")
**Expected:** Browser opens with URL
**Verify:** get_foreground_app() returns browser package

---

### 6. Wait Tests (3 tests)

#### TEST_026: wait_for_element Finds Delayed Element
**Setup:** Test harness app on Form screen (just loaded)
**Action:** wait_for_element(selector={resource_id="delayed_text"}, timeout=5000)
**Expected:** Element found after 2-second delay
**Verify:** Returns element, elapsed time ~2000ms

#### TEST_027: wait_for_element Times Out Correctly
**Setup:** Test harness app on Login screen
**Action:** wait_for_element(selector={text="NonExistent"}, timeout=2000)
**Expected:** Returns TIMEOUT error after 2 seconds
**Verify:** Error code = TIMEOUT, elapsed ~2000ms

#### TEST_028: wait_for_idle Detects Stable UI
**Setup:** Test harness app on Form screen (animations settling)
**Action:** wait_for_idle(timeout=3000)
**Expected:** Returns success when UI stops changing
**Verify:** Response success=true, elapsed time < 3000ms

---

### 7. Error Handling Tests (2 tests)

#### TEST_029: Tap Nonexistent Selector Returns ELEMENT_NOT_FOUND
**Setup:** Test harness app on Login screen
**Action:** tap(selector={text="NonExistent"})
**Expected:** Error response with code ELEMENT_NOT_FOUND
**Verify:** Error message suggests screenshot + get_ui_tree

#### TEST_030: Invalid Selector Returns INVALID_REQUEST
**Setup:** Test harness app running
**Action:** tap(selector={invalid_field="value"})
**Expected:** Error response with code INVALID_REQUEST
**Verify:** Error message describes valid selector format

---

### 8. Integration Flow Tests (BONUS: 3+ tests)

#### TEST_031: Complete Login Flow
**Action:** Launch test app → input username → input password → tap login
**Expected:** Successfully navigates to List screen
**Verify:** All steps succeed, final screen is ListActivity

#### TEST_032: Form Submission Flow
**Action:** Navigate to Form → check checkbox → select radio → enter date → submit
**Expected:** Toast "Form submitted successfully!" appears
**Verify:** All form fields correctly filled

#### TEST_033: List Scroll and Navigation
**Action:** Navigate to List → scroll to item 20 → tap item 20 → verify Form screen
**Expected:** Scroll succeeds, navigation to Form works
**Verify:** Final screen is FormActivity

---

## Test Execution Strategy

### Phase 1: Smoke Tests (Run First)
- TEST_001 (Connection)
- TEST_004 (UI Tree)
- TEST_005 (Screenshot)
- TEST_012 (Tap Coordinates)

### Phase 2: Core Functionality (Run Second)
- All Observe tests
- All Gesture tests
- All Input tests

### Phase 3: Advanced Features (Run Third)
- Wait tests
- App Management tests
- Error handling tests

### Phase 4: Integration Flows (Run Last)
- TEST_031, TEST_032, TEST_033

### CI/CD Integration
```bash
# Run all integration tests
TEST_DEVICE_ID=emulator-5554 cargo test --test integration_tests -- --nocapture

# Run specific category
cargo test --test integration_tests observe -- --nocapture
```

---

## Coverage Matrix

| MCP Tool | Test Count | Test IDs |
|----------|------------|----------|
| get_ui_tree | 4 | 004, 011, 031, 032 |
| screenshot | 2 | 005, 006 |
| find_elements | 4 | 007, 008, 009, 011 |
| get_foreground_app | 3 | 010, 023, 025 |
| tap | 4 | 012, 013, 014, 029 |
| long_press | 1 | 015 |
| swipe | 1 | 016 |
| press_key | 2 | 017, 018 |
| global_action | 1 | 019 |
| input_text | 3 | 020, 021, 022 |
| launch_app | 1 | 023 |
| close_app | 1 | 024 |
| open_url | 1 | 025 |
| wait_for_element | 2 | 026, 027 |
| wait_for_idle | 1 | 028 |

**Total Coverage:** 16/16 Phase 1 MCP tools (100%)
**Total Tests:** 33 scenarios (exceeds 30+ requirement)

---

## Next Steps

**Waiting for:**
- ✅ Task #4: Selector Resolution in CommandHandler (android-engineer)
- ✅ Task #8: Wait Tools + Remaining Handlers (android-engineer)

**When unblocked:**
1. Implement test infrastructure (setup/teardown helpers)
2. Implement tests in order: Connection → Observe → Gesture → Input → Wait → Error
3. Run tests against live emulator
4. Fix any failures
5. Document test results and coverage
6. Mark Task #9 as complete

---

**Estimated Implementation Time:** 2-3 days
**Test Execution Time:** ~5-10 minutes for full suite
**Prepared by:** team-lead (2026-02-10)
