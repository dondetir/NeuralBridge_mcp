# Installation Scripts

Quick installation scripts for NeuralBridge MCP Server.

## Usage

### Linux / macOS

```bash
curl -fsSL https://raw.githubusercontent.com/neuralbridge/neuralbridge/main/scripts/install.sh | bash
```

Or download and run manually:
```bash
wget https://raw.githubusercontent.com/neuralbridge/neuralbridge/main/scripts/install.sh
chmod +x install.sh
./install.sh
```

### Windows

Open PowerShell as Administrator:
```powershell
irm https://raw.githubusercontent.com/neuralbridge/neuralbridge/main/scripts/install.ps1 | iex
```

Or download and run manually:
```powershell
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/neuralbridge/neuralbridge/main/scripts/install.ps1" -OutFile install.ps1
powershell -ExecutionPolicy Bypass -File install.ps1
```

## What the Scripts Do

1. Detect OS and architecture
2. Download latest release binary from GitHub
3. Install to system PATH (`/usr/local/bin` or `Program Files`)
4. Verify installation
5. Display next steps

## Manual Installation

If you prefer to install manually, see [DISTRIBUTION.md](../docs/DISTRIBUTION.md) for detailed instructions.

## Troubleshooting

### "Permission denied"
- Linux/macOS: Run with sudo or install to user directory
- Windows: Run PowerShell as Administrator

### "Command not found" after installation
- Restart your terminal
- Or manually add to PATH:
  - Linux/macOS: `export PATH="$PATH:/usr/local/bin"`
  - Windows: Add installation directory to System Environment Variables

### "Download failed"
- Check internet connection
- Verify GitHub releases exist: https://github.com/neuralbridge/neuralbridge/releases
- Try manual download and installation

## Advanced Options

### Custom Installation Directory

**Linux/macOS:**
```bash
INSTALL_DIR="$HOME/.local/bin" ./install.sh
```

**Windows:**
```powershell
.\install.ps1 -InstallDir "$env:LOCALAPPDATA\NeuralBridge"
```

### Specific Version

Modify the script to download a specific version:
```bash
# Edit VERSION variable
VERSION="0.9.0"
```

## Uninstall

**Linux/macOS:**
```bash
sudo rm /usr/local/bin/neuralbridge-mcp
```

**Windows:**
```powershell
Remove-Item "$env:ProgramFiles\NeuralBridge" -Recurse -Force
# Then remove from PATH via System Environment Variables
```
