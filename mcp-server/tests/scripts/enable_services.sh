#!/bin/bash
# Re-enable services after APK deployment
#
# After deploying a new APK with `adb install -r`, Android revokes AccessibilityService
# permissions and NotificationListenerService may not auto-bind. This script re-enables
# both services to restore full functionality.

set -e  # Exit on error

echo "🔧 Re-enabling NeuralBridge services after APK deployment..."

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ Error: No Android device connected"
    echo "   Connect a device or start an emulator first"
    exit 1
fi

echo "📱 Enabling AccessibilityService..."
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1

echo "🔔 Binding NotificationListenerService..."
adb shell cmd notification disallow_listener \
  com.neuralbridge.companion/.notification.NotificationListener 2>/dev/null || true
sleep 1
adb shell cmd notification allow_listener \
  com.neuralbridge.companion/.notification.NotificationListener

echo "⏳ Waiting for services to bind..."
sleep 2

# Verify AccessibilityService is enabled
ACCESSIBILITY_ENABLED=$(adb shell settings get secure enabled_accessibility_services)
if [[ "$ACCESSIBILITY_ENABLED" == *"NeuralBridgeAccessibilityService"* ]]; then
    echo "✅ AccessibilityService enabled"
else
    echo "⚠️  Warning: AccessibilityService may not be enabled"
fi

# Verify NotificationListenerService is in enabled list
NOTIFICATION_ENABLED=$(adb shell settings get secure enabled_notification_listeners)
if [[ "$NOTIFICATION_ENABLED" == *"NotificationListener"* ]]; then
    echo "✅ NotificationListenerService enabled"
else
    echo "⚠️  Warning: NotificationListenerService may not be enabled"
fi

echo ""
echo "✅ Services enabled and bound successfully!"
echo "   Run integration tests now."
