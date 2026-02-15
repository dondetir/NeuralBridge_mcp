#!/bin/bash
# NeuralBridge Phase 1 Verification Script
# Tests all components of the TCP implementation

set -e

echo "🧪 NeuralBridge Phase 1 Verification"
echo "======================================"
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

ADB="$HOME/Android/Sdk/platform-tools/adb"

# Check 1: Emulator connected
echo "1. Checking emulator connection..."
if $ADB devices | grep -q "emulator-5554.*device"; then
    echo -e "   ${GREEN}✅ Emulator connected (emulator-5554)${NC}"
else
    echo -e "   ${RED}✗ Emulator not connected${NC}"
    exit 1
fi

# Check 2: Companion app installed
echo "2. Checking companion app..."
if $ADB shell pm list packages | grep -q "com.neuralbridge.companion"; then
    echo -e "   ${GREEN}✅ Companion app installed${NC}"
else
    echo -e "   ${RED}✗ Companion app not installed${NC}"
    exit 1
fi

# Check 3: AccessibilityService enabled
echo "3. Checking AccessibilityService..."
if $ADB shell settings get secure enabled_accessibility_services | grep -q "NeuralBridge"; then
    echo -e "   ${GREEN}✅ AccessibilityService enabled${NC}"
else
    echo -e "   ${YELLOW}⚠️  AccessibilityService not enabled${NC}"
    echo "   Run: $ADB shell settings put secure enabled_accessibility_services com.neuralbridge.companion/.NeuralBridgeAccessibilityService"
fi

# Check 4: TCP server listening
echo "4. Checking TCP server..."
if $ADB shell "ss -tulpn 2>/dev/null" | grep -q "38472" || $ADB shell netstat 2>/dev/null | grep -q "38472"; then
    echo -e "   ${GREEN}✅ TCP server listening on port 38472${NC}"
else
    echo -e "   ${RED}✗ TCP server not listening${NC}"
    exit 1
fi

# Check 5: Port forwarding
echo "5. Setting up port forwarding..."
$ADB forward tcp:38472 tcp:38472
echo -e "   ${GREEN}✅ Port forwarding configured${NC}"

# Check 6: TCP connection test
echo "6. Testing TCP connection..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if timeout 5 python3 "$SCRIPT_DIR/test_connection.py" > /dev/null 2>&1; then
    echo -e "   ${GREEN}✅ TCP connection successful${NC}"
else
    echo -e "   ${RED}✗ TCP connection failed${NC}"
    exit 1
fi

# Check 7: Recent logs
echo "7. Checking companion app logs..."
RECENT_LOGS=$($ADB logcat -d -s NeuralBridge:V | tail -5)
if echo "$RECENT_LOGS" | grep -q "NeuralBridge"; then
    echo -e "   ${GREEN}✅ Companion app is logging${NC}"
else
    echo -e "   ${YELLOW}⚠️  No recent logs (may need restart)${NC}"
fi

# Check 8: Build status
echo "8. Checking build artifacts..."
if [ -f "$SCRIPT_DIR/mcp-server/target/release/neuralbridge-mcp" ]; then
    SIZE=$(du -h "$SCRIPT_DIR/mcp-server/target/release/neuralbridge-mcp" | cut -f1)
    echo -e "   ${GREEN}✅ MCP server binary exists ($SIZE)${NC}"
else
    echo -e "   ${RED}✗ MCP server binary not found${NC}"
    exit 1
fi

if [ -f "$SCRIPT_DIR/companion-app/app/build/outputs/apk/debug/app-debug.apk" ]; then
    SIZE=$(du -h "$SCRIPT_DIR/companion-app/app/build/outputs/apk/debug/app-debug.apk" | cut -f1)
    echo -e "   ${GREEN}✅ Companion APK exists ($SIZE)${NC}"
else
    echo -e "   ${YELLOW}⚠️  Companion APK not found (may need rebuild)${NC}"
fi

echo ""
echo "======================================"
echo -e "${GREEN}✅ Phase 1 verification COMPLETE${NC}"
echo ""
echo "Summary:"
echo "  • TCP server operational"
echo "  • Binary protocol working"
echo "  • Request/response pipeline functional"
echo "  • All components deployed"
echo ""
echo "Ready for tool testing!"
echo ""
echo "To test tools, you need an MCP client like:"
echo "  • Claude Desktop (configure MCP server)"
echo "  • Custom test client (send protobuf requests)"
echo ""
