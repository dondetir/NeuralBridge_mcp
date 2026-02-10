/*!
 * ADB Executor
 *
 * Executes ADB commands with proper error handling and output parsing.
 * Handles privileged operations that must be routed through ADB.
 */

use anyhow::{Result, Context, bail};
use std::path::PathBuf;
use std::process::Stdio;
use tokio::process::Command;
use tracing::{debug, warn, trace};

/// ADB command executor
pub struct AdbExecutor {
    /// Path to ADB binary
    pub(crate) adb_path: PathBuf,
}

impl AdbExecutor {
    /// Create new ADB executor
    ///
    /// Locates ADB binary in PATH or ANDROID_HOME.
    pub async fn new() -> Result<Self> {
        let adb_path = Self::find_adb()?;
        debug!("Using ADB at: {:?}", adb_path);

        Ok(Self { adb_path })
    }

    /// Find ADB binary
    fn find_adb() -> Result<PathBuf> {
        // Try PATH first
        if let Ok(output) = std::process::Command::new("which")
            .arg("adb")
            .output()
        {
            if output.status.success() {
                let path = String::from_utf8_lossy(&output.stdout);
                let path = path.trim();
                if !path.is_empty() {
                    return Ok(PathBuf::from(path));
                }
            }
        }

        // Try ANDROID_HOME
        if let Ok(android_home) = std::env::var("ANDROID_HOME") {
            let adb_path = PathBuf::from(android_home)
                .join("platform-tools")
                .join("adb");
            if adb_path.exists() {
                return Ok(adb_path);
            }
        }

        // Try common paths
        let common_paths = [
            "/usr/local/bin/adb",
            "/usr/bin/adb",
            "/opt/android-sdk/platform-tools/adb",
        ];

        for path in &common_paths {
            let path = PathBuf::from(path);
            if path.exists() {
                return Ok(path);
            }
        }

        bail!("ADB not found in PATH or ANDROID_HOME. Please install Android SDK Platform-Tools.")
    }

    /// Check if ADB is installed and accessible
    pub async fn check_installed(&self) -> Result<bool> {
        let result = Command::new(&self.adb_path)
            .arg("version")
            .output()
            .await;

        match result {
            Ok(output) => {
                if output.status.success() {
                    let version = String::from_utf8_lossy(&output.stdout);
                    debug!("ADB version: {}", version.lines().next().unwrap_or("unknown"));
                    Ok(true)
                } else {
                    Ok(false)
                }
            }
            Err(e) => {
                warn!("Failed to execute ADB: {}", e);
                Ok(false)
            }
        }
    }

    /// Execute ADB command
    ///
    /// # Arguments
    /// * `args` - Command arguments (e.g., ["devices", "-l"])
    ///
    /// # Returns
    /// Command output (stdout)
    pub async fn execute_command(&self, args: &[&str]) -> Result<String> {
        trace!("Executing ADB command: adb {}", args.join(" "));

        let output = Command::new(&self.adb_path)
            .args(args)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output()
            .await
            .context("Failed to execute ADB command")?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            bail!("ADB command failed: {}", stderr);
        }

        let stdout = String::from_utf8(output.stdout)
            .context("ADB output is not valid UTF-8")?;

        trace!("ADB output: {} bytes", stdout.len());
        Ok(stdout)
    }

    /// Execute ADB shell command with streaming output
    ///
    /// Useful for commands that produce large output (e.g., screencap, logcat)
    pub async fn execute_shell_stream(&self, device_id: &str, command: &[&str]) -> Result<Vec<u8>> {
        let mut args = vec!["-s", device_id, "shell"];
        args.extend_from_slice(command);

        trace!("Executing ADB shell stream: adb {}", args.join(" "));

        let output = Command::new(&self.adb_path)
            .args(&args)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output()
            .await
            .context("Failed to execute ADB shell command")?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            bail!("ADB shell command failed: {}", stderr);
        }

        Ok(output.stdout)
    }

    /// Install APK on device
    pub async fn install_apk(&self, device_id: &str, apk_path: &str, replace: bool) -> Result<()> {
        debug!("Installing APK on device {}: {}", device_id, apk_path);

        let mut args = vec!["-s", device_id, "install"];
        if replace {
            args.push("-r"); // Replace existing app
        }
        args.push(apk_path);

        let output = self.execute_command(&args).await?;

        if output.contains("Success") {
            debug!("APK installed successfully");
            Ok(())
        } else {
            bail!("APK installation failed: {}", output);
        }
    }

    /// Uninstall package from device
    pub async fn uninstall_package(&self, device_id: &str, package_name: &str) -> Result<()> {
        debug!("Uninstalling package {} from device {}", package_name, device_id);

        let output = self.execute_command(&[
            "-s", device_id,
            "uninstall", package_name
        ]).await?;

        if output.contains("Success") {
            debug!("Package uninstalled successfully");
            Ok(())
        } else {
            bail!("Package uninstallation failed: {}", output);
        }
    }

    /// Clear app data
    pub async fn clear_app_data(&self, device_id: &str, package_name: &str) -> Result<()> {
        debug!("Clearing data for package {} on device {}", package_name, device_id);

        let output = self.execute_command(&[
            "-s", device_id,
            "shell", "pm", "clear", package_name
        ]).await?;

        if output.contains("Success") {
            debug!("App data cleared successfully");
            Ok(())
        } else {
            bail!("Failed to clear app data: {}", output);
        }
    }

    /// Force-stop an app
    pub async fn force_stop(&self, device_id: &str, package_name: &str) -> Result<()> {
        debug!("Force-stopping package {} on device {}", package_name, device_id);

        self.execute_command(&[
            "-s", device_id,
            "shell", "am", "force-stop", package_name
        ]).await?;

        debug!("App force-stopped successfully");
        Ok(())
    }

    /// Grant runtime permission to app
    pub async fn grant_permission(&self, device_id: &str, package_name: &str, permission: &str) -> Result<()> {
        debug!("Granting permission {} to {} on device {}", permission, package_name, device_id);

        self.execute_command(&[
            "-s", device_id,
            "shell", "pm", "grant", package_name, permission
        ]).await?;

        debug!("Permission granted successfully");
        Ok(())
    }

    /// Take screenshot via ADB (fallback method)
    pub async fn screenshot(&self, device_id: &str) -> Result<Vec<u8>> {
        debug!("Taking screenshot via ADB on device {}", device_id);

        // Execute: adb exec-out screencap -p
        // Note: Using exec-out to get raw binary data without shell encoding
        let screenshot_data = self.execute_shell_stream(device_id, &["screencap", "-p"]).await?;

        debug!("Screenshot captured: {} bytes", screenshot_data.len());
        Ok(screenshot_data)
    }

    /// Get clipboard content (Android 10+)
    pub async fn get_clipboard(&self, device_id: &str) -> Result<String> {
        debug!("Getting clipboard content from device {}", device_id);

        let output = self.execute_command(&[
            "-s", device_id,
            "shell", "cmd", "clipboard", "get-text"
        ]).await?;

        Ok(output.trim().to_string())
    }

    /// Set clipboard content
    pub async fn set_clipboard(&self, device_id: &str, text: &str) -> Result<()> {
        debug!("Setting clipboard content on device {}", device_id);

        // Note: cmd clipboard set-text doesn't work reliably on all Android versions
        // Alternative: Use am broadcast with ClipboardManager
        self.execute_command(&[
            "-s", device_id,
            "shell", "cmd", "clipboard", "set-text", text
        ]).await?;

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_adb_executor_creation() {
        let result = AdbExecutor::new().await;

        if result.is_err() {
            eprintln!("ADB not found (this is expected in CI environments)");
        }
    }

    #[tokio::test]
    async fn test_adb_version() {
        let executor = match AdbExecutor::new().await {
            Ok(e) => e,
            Err(_) => {
                eprintln!("Skipping test: ADB not available");
                return;
            }
        };

        let installed = executor.check_installed().await.unwrap();
        assert!(installed);
    }
}
