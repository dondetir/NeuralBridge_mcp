# NeuralBridge Test Harness App

A minimal Android app for integration testing of the NeuralBridge automation platform.

## Overview

This app provides 3 screens with various UI elements to test AI-driven automation:
- Login screen (text input, button)
- List screen (RecyclerView with 20 items)
- Form screen (checkbox, radio buttons, date input, delayed element)

## Build & Install

```bash
# Build APK
cd test-app
./gradlew assembleDebug

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch app
adb shell am start -n com.neuralbridge.testapp/.LoginActivity
```

## UI Elements & Resource IDs

### Login Screen (`LoginActivity`)
- **Username:** `@+id/username` (EditText)
- **Password:** `@+id/password` (EditText)
- **Login Button:** `@+id/button_login` (Button with text "Login")

**Behavior:**
- Shows toast "Login successful!" on valid input
- Navigates to List screen on submit

### List Screen (`ListActivity`)
- **RecyclerView:** `@+id/recycler_view`
- **Items:** 20 items with text "Item 1", "Item 2", ..., "Item 20"
- **Item text:** `@+id/item_text`

**Behavior:**
- Click on any item navigates to Form screen

### Form Screen (`FormActivity`)
- **Checkbox:** `@+id/checkbox_terms` (text: "I agree to terms and conditions")
- **Radio Group:** `@+id/radio_group_options`
  - `@+id/radio_option1` (text: "Option 1")
  - `@+id/radio_option2` (text: "Option 2")
  - `@+id/radio_option3` (text: "Option 3")
- **Date Input:** `@+id/date_input` (EditText, hint: "Enter date (YYYY-MM-DD)")
- **Submit Button:** `@+id/button_submit` (Button with text: "Submit Form")
- **Delayed Text:** `@+id/delayed_text` (appears after 2 seconds)

**Behavior:**
- Delayed text appears 2 seconds after screen load
- Shows toast "Form submitted successfully!" if all fields filled
- Shows toast "Please fill all fields" if validation fails

## Testing Examples

### Manual Test Flow
```bash
# 1. Login
adb shell input text "testuser"
adb shell input keyevent 61  # Tab
adb shell input text "password"
adb shell input tap 640 1905  # Click login button

# 2. Navigate to Form
adb shell input tap 640 800  # Click first list item

# 3. Fill Form
adb shell input tap 100 640  # Check checkbox
adb shell input tap 180 900  # Select radio option 1
adb shell input tap 640 1410  # Focus date input
adb shell input text "2026-02-10"
adb shell input tap 640 1620  # Submit
```

## Package Information
- **Package Name:** `com.neuralbridge.testapp`
- **minSdk:** 24 (Android 7.0)
- **targetSdk:** 34 (Android 14)
- **APK Size:** ~5.6 MB

## Dependencies
- AndroidX Core, AppCompat, Activity
- Material Design Components
- RecyclerView
- ConstraintLayout

## Test Coverage

This app is designed to test:
- ✅ Text input (username, password, date)
- ✅ Button clicks
- ✅ RecyclerView item clicks
- ✅ Checkbox state
- ✅ Radio button selection
- ✅ Delayed element visibility (wait testing)
- ✅ Navigation between activities
- ✅ Toast messages
- ✅ Stable resource IDs for element selection

## Verification Status

✅ App builds successfully (5.6 MB APK)
✅ All 3 screens implemented and functional
✅ All elements have stable resource IDs
✅ App installed on emulator-5554
✅ Manual navigation test completed (Login → List → Form)
✅ All UI elements verified in UI hierarchy
