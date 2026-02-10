/*!
 * Management Tools - Implementation helpers
 *
 * Contains the business logic for app management tools.
 * Tool entry points are defined as methods on NeuralBridgeServer in main.rs.
 *
 * This module will hold:
 * - ADB command building for privileged operations
 * - App launch intent construction
 * - Package validation logic
 */

use anyhow::Result;

/// Build ADB command to force-stop an app
#[allow(dead_code)]
pub fn build_force_stop_command(package_name: &str) -> Vec<String> {
    vec![
        "shell".to_string(),
        "am".to_string(),
        "force-stop".to_string(),
        package_name.to_string(),
    ]
}

/// Build ADB command to clear app data
#[allow(dead_code)]
pub fn build_clear_data_command(package_name: &str) -> Vec<String> {
    vec![
        "shell".to_string(),
        "pm".to_string(),
        "clear".to_string(),
        package_name.to_string(),
    ]
}

/// Build ADB command to install an APK
#[allow(dead_code)]
pub fn build_install_command(apk_path: &str) -> Vec<String> {
    vec![
        "install".to_string(),
        "-r".to_string(),
        apk_path.to_string(),
    ]
}

/// Validate package name format (basic check)
#[allow(dead_code)]
pub fn validate_package_name(package: &str) -> Result<()> {
    if package.is_empty() {
        anyhow::bail!("Package name cannot be empty");
    }
    if !package.contains('.') {
        anyhow::bail!("Invalid package name: {} (expected format: com.example.app)", package);
    }
    Ok(())
}
