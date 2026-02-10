#!/bin/bash
# NeuralBridge Python Demo - Quick Setup Script

set -e

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║                                                           ║"
echo "║     NeuralBridge Python MCP Demo - Quick Setup           ║"
echo "║                                                           ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""

# Check Python version
echo "Checking Python version..."
python_version=$(python3 --version 2>&1 | awk '{print $2}')
echo "✅ Python $python_version detected"
echo ""

# Create virtual environment
echo "Creating virtual environment..."
if [ -d "venv" ]; then
    echo "⚠️  Virtual environment already exists"
else
    python3 -m venv venv
    echo "✅ Virtual environment created"
fi
echo ""

# Activate and install dependencies
echo "Installing dependencies..."
source venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
echo "✅ Dependencies installed"
echo ""

# Verify MCP server binary
echo "Verifying MCP server binary..."
mcp_binary="../mcp-server/target/release/neuralbridge-mcp"
if [ -f "$mcp_binary" ]; then
    echo "✅ MCP server binary found: $mcp_binary"
else
    echo "❌ MCP server binary not found: $mcp_binary"
    echo ""
    echo "Build it with:"
    echo "  cd ../mcp-server && cargo build --release"
    exit 1
fi
echo ""

# ADB path
ADB="/home/rdondeti/Android/Sdk/platform-tools/adb"

# Check emulator
echo "Checking emulator status..."
if $ADB devices | grep -q "emulator-5554"; then
    echo "✅ Emulator running (emulator-5554)"
else
    echo "⚠️  Emulator not detected"
    echo "   Start emulator before running demo"
fi
echo ""

# Check companion app
echo "Checking companion app..."
if $ADB shell pm list packages | grep -q "com.neuralbridge.companion"; then
    echo "✅ Companion app installed"
else
    echo "⚠️  Companion app not found"
    echo "   Install from: ../companion-app/app/build/outputs/apk/debug/"
fi
echo ""

# Setup port forwarding
echo "Setting up port forwarding..."
$ADB forward tcp:38472 tcp:38472
echo "✅ Port forwarding configured (tcp:38472)"
echo ""

# Create screenshots directory
mkdir -p screenshots
echo "✅ Screenshots directory created"
echo ""

echo "═══════════════════════════════════════════════════════════"
echo "✅ Setup complete!"
echo ""
echo "To run the demo:"
echo "  source venv/bin/activate"
echo "  python -m demo_client.main"
echo ""
echo "Or run directly:"
echo "  ./run_demo.sh"
echo "═══════════════════════════════════════════════════════════"
