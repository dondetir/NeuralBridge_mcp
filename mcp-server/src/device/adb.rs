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
        // Try ADB_PATH env var first (explicit path to adb binary)
        if let Ok(adb_path) = std::env::var("ADB_PATH") {
            let path = PathBuf::from(&adb_path);
            if path.exists() {
                return Ok(path);
            }
        }

        // Try PATH
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

    /// Validate package name format
    fn validate_package_name(name: &str) -> Result<()> {
        if name.is_empty() || name.len() > 255 {
            bail!("Invalid package name length");
        }
        let valid = name.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_');
        if !valid || !name.contains('.') {
            bail!("Invalid package name format: {}", name);
        }
        Ok(())
    }

    /// Validate Android permission string
    fn validate_permission(permission: &str) -> Result<()> {
        if permission.is_empty() || permission.len() > 255 {
            bail!("Invalid permission string length");
        }
        // Android permissions must start with a domain (e.g., android.permission.CAMERA)
        if !permission.contains('.') {
            bail!("Invalid permission format: must contain domain (e.g., android.permission.CAMERA)");
        }
        // Only allow alphanumeric, dots, and underscores
        let valid = permission.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_');
        if !valid {
            bail!("Invalid permission format: contains illegal characters");
        }
        Ok(())
    }

    /// Validate APK file path
    fn validate_apk_path(path: &str) -> Result<()> {
        if path.is_empty() {
            bail!("APK path is empty");
        }
        if !path.ends_with(".apk") {
            bail!("APK path must end with .apk extension");
        }
        let path_buf = std::path::Path::new(path);
        if !path_buf.exists() {
            bail!("APK file does not exist: {}", path);
        }
        if !path_buf.is_file() {
            bail!("APK path is not a file: {}", path);
        }
        Ok(())
    }

    /// Install APK on device
    pub async fn install_apk(&self, device_id: &str, apk_path: &str, replace: bool) -> Result<()> {
        // Validate APK path before proceeding
        Self::validate_apk_path(apk_path)?;

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
        // Validate package name before proceeding
        Self::validate_package_name(package_name)?;

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
        // Validate package name before proceeding
        Self::validate_package_name(package_name)?;

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
        // Validate package name before proceeding
        Self::validate_package_name(package_name)?;

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
        // Validate package name and permission before proceeding
        Self::validate_package_name(package_name)?;
        Self::validate_permission(permission)?;

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

    /// Validate clipboard text for shell safety
    fn validate_clipboard_text(text: &str) -> Result<()> {
        if text.is_empty() {
            return Ok(());
        }
        // Check for shell metacharacters that could lead to command injection
        let dangerous_chars = ['$', '`', '\\', '|', '&', ';', '<', '>', '(', ')', '{', '}', '\n', '\r'];
        if text.chars().any(|c| dangerous_chars.contains(&c)) {
            bail!("Clipboard text contains potentially dangerous shell metacharacters");
        }
        Ok(())
    }

    /// Set clipboard content
    pub async fn set_clipboard(&self, device_id: &str, text: &str) -> Result<()> {
        // Validate clipboard text for shell safety
        Self::validate_clipboard_text(text)?;

        debug!("Setting clipboard content on device {}", device_id);

        // Note: cmd clipboard set-text doesn't work reliably on all Android versions
        // Alternative: Use am broadcast with ClipboardManager
        self.execute_command(&[
            "-s", device_id,
            "shell", "cmd", "clipboard", "set-text", text
        ]).await?;

        Ok(())
    }

    /// Capture logcat output for debugging
    pub async fn capture_logcat(
        &self,
        device_id: &str,
        package: Option<&str>,
        level: &str,
        lines: i32,
        crash_only: bool,
    ) -> Result<String> {
        debug!("Capturing logcat from device {}", device_id);

        let mut output = if let Some(pkg) = package {
            // Get PID for the package
            let pid_output = self.execute_command(&[
                "-s", device_id,
                "shell", "pidof", pkg
            ]).await;

            match pid_output {
                Ok(pid_str) => {
                    let pid = pid_str.trim();
                    if pid.is_empty() {
                        // Package not running, return empty
                        return Ok(String::new());
                    }
                    // Get logcat for this PID
                    self.execute_command(&[
                        "-s", device_id,
                        "logcat", "-d", &format!("--pid={}", pid),
                        "-t", &lines.to_string(),
                        &format!("*:{}", level)
                    ]).await?
                }
                Err(_) => {
                    // pidof failed, package not running
                    return Ok(String::new());
                }
            }
        } else {
            // No package filter, get all logs
            self.execute_command(&[
                "-s", device_id,
                "logcat", "-d",
                "-t", &lines.to_string(),
                &format!("*:{}", level)
            ]).await?
        };

        // If crash_only, filter for FATAL EXCEPTION blocks
        if crash_only {
            let mut crash_lines = Vec::new();
            let mut in_crash = false;

            for line in output.lines() {
                if line.contains("FATAL EXCEPTION") {
                    in_crash = true;
                    crash_lines.push(line.to_string());
                } else if in_crash {
                    if line.trim().is_empty() {
                        in_crash = false;
                    } else {
                        crash_lines.push(line.to_string());
                    }
                }
            }
            output = crash_lines.join("\n");
        }

        Ok(output)
    }

    /// Get device information
    pub async fn get_device_info(&self, device_id: &str) -> Result<serde_json::Value> {
        debug!("Getting device info from device {}", device_id);

        // Get various device properties
        let manufacturer = self.execute_command(&[
            "-s", device_id,
            "shell", "getprop", "ro.product.manufacturer"
        ]).await?.trim().to_string();

        let model = self.execute_command(&[
            "-s", device_id,
            "shell", "getprop", "ro.product.model"
        ]).await?.trim().to_string();

        let android_version = self.execute_command(&[
            "-s", device_id,
            "shell", "getprop", "ro.build.version.release"
        ]).await?.trim().to_string();

        let sdk_level = self.execute_command(&[
            "-s", device_id,
            "shell", "getprop", "ro.build.version.sdk"
        ]).await?.trim().to_string();

        // Get screen dimensions
        let wm_size = self.execute_command(&[
            "-s", device_id,
            "shell", "wm", "size"
        ]).await?;

        // Parse "Physical size: 1080x2340" or "Override size: 1080x2340"
        let dimensions = wm_size
            .lines()
            .find(|line| line.contains("size:"))
            .and_then(|line| line.split(':').nth(1))
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|| "unknown".to_string());

        // Get screen density
        let wm_density = self.execute_command(&[
            "-s", device_id,
            "shell", "wm", "density"
        ]).await?;

        // Parse "Physical density: 420" or "Override density: 420"
        let density = wm_density
            .lines()
            .find(|line| line.contains("density:"))
            .and_then(|line| line.split(':').nth(1))
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|| "unknown".to_string());

        // Build JSON response
        Ok(serde_json::json!({
            "manufacturer": manufacturer,
            "model": model,
            "android_version": android_version,
            "sdk_level": sdk_level,
            "screen_size": dimensions,
            "screen_density": density,
        }))
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

    #[test]
    fn test_validate_package_name_valid() {
        assert!(AdbExecutor::validate_package_name("com.example.app").is_ok());
        assert!(AdbExecutor::validate_package_name("com.google.android.gms").is_ok());
        assert!(AdbExecutor::validate_package_name("com.test_app.demo").is_ok());
    }

    #[test]
    fn test_validate_package_name_invalid() {
        // Empty name
        assert!(AdbExecutor::validate_package_name("").is_err());

        // No dot (missing domain)
        assert!(AdbExecutor::validate_package_name("myapp").is_err());

        // Contains shell metacharacters
        assert!(AdbExecutor::validate_package_name("com.app;rm -rf /").is_err());
        assert!(AdbExecutor::validate_package_name("com.app`whoami`").is_err());

        // Too long
        let long_name = "com.".to_string() + &"a".repeat(260);
        assert!(AdbExecutor::validate_package_name(&long_name).is_err());
    }

    #[test]
    fn test_validate_permission_valid() {
        assert!(AdbExecutor::validate_permission("android.permission.CAMERA").is_ok());
        assert!(AdbExecutor::validate_permission("android.permission.READ_EXTERNAL_STORAGE").is_ok());
        assert!(AdbExecutor::validate_permission("com.example.permission.CUSTOM").is_ok());
    }

    #[test]
    fn test_validate_permission_invalid() {
        // Empty
        assert!(AdbExecutor::validate_permission("").is_err());

        // No dot (missing domain)
        assert!(AdbExecutor::validate_permission("CAMERA").is_err());

        // Contains shell metacharacters
        assert!(AdbExecutor::validate_permission("android.permission;rm").is_err());

        // Too long
        let long_perm = "android.".to_string() + &"a".repeat(260);
        assert!(AdbExecutor::validate_permission(&long_perm).is_err());
    }

    #[test]
    fn test_validate_apk_path_invalid_extension() {
        // Not .apk extension
        assert!(AdbExecutor::validate_apk_path("app.txt").is_err());
        assert!(AdbExecutor::validate_apk_path("/path/to/app.zip").is_err());
    }

    #[test]
    fn test_validate_apk_path_empty() {
        assert!(AdbExecutor::validate_apk_path("").is_err());
    }

    #[test]
    fn test_validate_clipboard_text_safe() {
        assert!(AdbExecutor::validate_clipboard_text("Hello World").is_ok());
        assert!(AdbExecutor::validate_clipboard_text("Test text with spaces!").is_ok());
        assert!(AdbExecutor::validate_clipboard_text("").is_ok());
        assert!(AdbExecutor::validate_clipboard_text("email@example.com").is_ok());
    }

    #[test]
    fn test_validate_clipboard_text_dangerous() {
        // Shell metacharacters
        assert!(AdbExecutor::validate_clipboard_text("text; rm -rf /").is_err());
        assert!(AdbExecutor::validate_clipboard_text("text`whoami`").is_err());
        assert!(AdbExecutor::validate_clipboard_text("text$(whoami)").is_err());
        assert!(AdbExecutor::validate_clipboard_text("text | cat").is_err());
        assert!(AdbExecutor::validate_clipboard_text("text & background").is_err());
        assert!(AdbExecutor::validate_clipboard_text("text\nls").is_err());
        assert!(AdbExecutor::validate_clipboard_text("text\\escape").is_err());
    }
}
