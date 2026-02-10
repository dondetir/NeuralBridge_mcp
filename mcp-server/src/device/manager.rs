/*!
 * Device Manager
 *
 * Manages Android device discovery and connection lifecycle.
 * Integrates with ADB for device enumeration and port forwarding setup.
 */

use anyhow::Result;
use std::collections::HashMap;
use tokio::sync::RwLock;
use tracing::{info, debug, warn};

use super::adb::AdbExecutor;

/// Device manager handles discovery and lifecycle of Android devices
pub struct DeviceManager {
    /// ADB command executor
    adb: AdbExecutor,

    /// Cache of discovered devices (device_id -> DeviceInfo)
    devices: RwLock<HashMap<String, DeviceInfo>>,
}

/// Information about a discovered device
#[derive(Debug, Clone)]
pub struct DeviceInfo {
    /// Device ID (e.g., "emulator-5554")
    pub device_id: String,

    /// Device state: "device", "offline", "unauthorized"
    pub state: String,

    /// Device model (if available)
    pub model: Option<String>,

    /// Android version (if available)
    pub android_version: Option<String>,
}

impl DeviceManager {
    /// Create new device manager
    pub async fn new() -> Result<Self> {
        let adb = AdbExecutor::new().await?;

        Ok(Self {
            adb,
            devices: RwLock::new(HashMap::new()),
        })
    }

    /// Check if ADB is installed and accessible
    pub async fn check_adb_installed(&self) -> Result<bool> {
        self.adb.check_installed().await
    }

    /// Discover connected Android devices
    ///
    /// Executes `adb devices -l` and parses output.
    ///
    /// # Returns
    /// List of device IDs
    pub async fn discover_devices(&self) -> Result<Vec<String>> {
        info!("Discovering Android devices...");

        // Execute `adb devices -l`
        let output = self.adb.execute_command(&["devices", "-l"]).await?;

        // Parse output
        let devices = self.parse_devices_output(&output)?;

        // Update cache
        let mut cache = self.devices.write().await;
        cache.clear();
        for device in &devices {
            cache.insert(device.device_id.clone(), device.clone());
        }

        let device_ids: Vec<String> = devices.iter()
            .map(|d| d.device_id.clone())
            .collect();

        info!("Found {} device(s): {:?}", device_ids.len(), device_ids);

        Ok(device_ids)
    }

    /// Parse `adb devices -l` output
    ///
    /// Example output:
    /// ```text
    /// List of devices attached
    /// emulator-5554          device product:sdk_phone_x86_64 model:sdk_phone_x86_64 device:generic_x86_64
    /// ```
    fn parse_devices_output(&self, output: &str) -> Result<Vec<DeviceInfo>> {
        let mut devices = Vec::new();

        for line in output.lines().skip(1) {
            // Skip header line and empty lines
            let line = line.trim();
            if line.is_empty() {
                continue;
            }

            // Parse line: "device_id  state  key:value key:value ..."
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() < 2 {
                continue;
            }

            let device_id = parts[0].to_string();
            let state = parts[1].to_string();

            // Skip offline/unauthorized devices
            if state != "device" {
                warn!("Skipping device {} with state: {}", device_id, state);
                continue;
            }

            // Parse optional metadata
            let mut model = None;
            let android_version = None;

            for part in &parts[2..] {
                if let Some((key, value)) = part.split_once(':') {
                    match key {
                        "model" => model = Some(value.to_string()),
                        "product" => {}, // Ignore for now
                        "device" => {}, // Ignore for now
                        _ => {}
                    }
                }
            }

            devices.push(DeviceInfo {
                device_id,
                state,
                model,
                android_version,
            });
        }

        Ok(devices)
    }

    /// Set up ADB port forwarding for a device
    ///
    /// Forwards host port 38472 to device port 38472.
    pub async fn setup_port_forwarding(&self, device_id: &str) -> Result<()> {
        info!("Setting up port forwarding for device: {}", device_id);

        // Execute: adb -s <device_id> forward tcp:38472 tcp:38472
        self.adb.execute_command(&[
            "-s", device_id,
            "forward", "tcp:38472", "tcp:38472"
        ]).await?;

        info!("Port forwarding established");
        Ok(())
    }

    /// Remove port forwarding for a device
    pub async fn remove_port_forwarding(&self, device_id: &str) -> Result<()> {
        info!("Removing port forwarding for device: {}", device_id);

        // Execute: adb -s <device_id> forward --remove tcp:38472
        self.adb.execute_command(&[
            "-s", device_id,
            "forward", "--remove", "tcp:38472"
        ]).await?;

        Ok(())
    }

    /// Check if companion app is installed on device
    pub async fn check_companion_installed(&self, device_id: &str) -> Result<bool> {
        debug!("Checking if companion app is installed on {}", device_id);

        // Execute: adb -s <device_id> shell pm list packages | grep com.neuralbridge
        let output = self.adb.execute_command(&[
            "-s", device_id,
            "shell", "pm", "list", "packages", "com.neuralbridge.companion"
        ]).await?;

        Ok(output.contains("com.neuralbridge.companion"))
    }

    /// Check if AccessibilityService is enabled on device
    pub async fn check_accessibility_enabled(&self, device_id: &str) -> Result<bool> {
        debug!("Checking if AccessibilityService is enabled on {}", device_id);

        // Execute: adb -s <device_id> shell settings get secure enabled_accessibility_services
        let output = self.adb.execute_command(&[
            "-s", device_id,
            "shell", "settings", "get", "secure", "enabled_accessibility_services"
        ]).await?;

        Ok(output.contains("com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService"))
    }

    /// Get device information
    pub async fn get_device_info(&self, device_id: &str) -> Result<Option<DeviceInfo>> {
        let cache = self.devices.read().await;
        Ok(cache.get(device_id).cloned())
    }

    /// Execute ADB shell command on device
    pub async fn execute_shell_command(&self, device_id: &str, command: &[&str]) -> Result<String> {
        let mut args = vec!["-s", device_id, "shell"];
        args.extend_from_slice(command);
        self.adb.execute_command(&args).await
    }

    /// Get ADB executor reference
    pub fn adb(&self) -> &AdbExecutor {
        &self.adb
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_devices_output() {
        let manager = DeviceManager {
            adb: AdbExecutor { adb_path: "adb".into() },
            devices: RwLock::new(HashMap::new()),
        };

        let output = r#"List of devices attached
emulator-5554          device product:sdk_phone_x86_64 model:sdk_phone_x86_64 device:generic_x86_64
192.168.1.100:5555     device
"#;

        let devices = manager.parse_devices_output(output).unwrap();
        assert_eq!(devices.len(), 2);
        assert_eq!(devices[0].device_id, "emulator-5554");
        assert_eq!(devices[0].state, "device");
        assert_eq!(devices[0].model, Some("sdk_phone_x86_64".to_string()));
    }

    #[test]
    fn test_parse_devices_offline() {
        let manager = DeviceManager {
            adb: AdbExecutor { adb_path: "adb".into() },
            devices: RwLock::new(HashMap::new()),
        };

        let output = r#"List of devices attached
emulator-5554          offline
"#;

        let devices = manager.parse_devices_output(output).unwrap();
        assert_eq!(devices.len(), 0); // Offline devices are filtered out
    }
}
