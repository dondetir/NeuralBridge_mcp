/*!
 * Selector Parser
 *
 * Parses selector strings into structured Selector protobuf messages.
 * Supports CSS-like selector syntax for UI element matching.
 *
 * Selector Syntax Examples:
 * - `text="Login"` - Match by exact text
 * - `text~="login"` - Match by partial text (case-insensitive)
 * - `#login_button` - Match by resource ID
 * - `.Button` - Match by class name
 * - `[contentDescription="Submit"]` - Match by content description
 * - `text="Login"[visible=true]` - Match visible elements with text "Login"
 */

use anyhow::{Result, bail};
use tracing::debug;

use crate::protocol::pb::Selector;

/// Parse selector string into Selector protobuf message
#[allow(dead_code)]
pub fn parse_selector(selector_str: &str) -> Result<Selector> {
    debug!("Parsing selector: {}", selector_str);

    let mut selector = Selector::default();

    // TODO Week 4: Implement full selector parser
    // For now, implement basic text matching

    // Simple text matching: "text"
    if selector_str.starts_with('"') && selector_str.ends_with('"') {
        let text = selector_str.trim_matches('"');
        selector.text = text.to_string();
        selector.exact_match = false; // Partial match by default
        return Ok(selector);
    }

    // Resource ID: #id_name
    if selector_str.starts_with('#') {
        let id = selector_str.trim_start_matches('#');
        selector.resource_id = id.to_string();
        return Ok(selector);
    }

    // Class name: .ClassName
    if selector_str.starts_with('.') {
        let class = selector_str.trim_start_matches('.');
        selector.class_name = class.to_string();
        return Ok(selector);
    }

    // Attribute syntax: [attr="value"]
    if selector_str.starts_with('[') && selector_str.ends_with(']') {
        return parse_attribute_selector(selector_str);
    }

    bail!("Invalid selector syntax: {}", selector_str);
}

/// Parse attribute selector: [attr="value"]
#[allow(dead_code)]
fn parse_attribute_selector(selector_str: &str) -> Result<Selector> {
    let inner = selector_str.trim_matches(|c| c == '[' || c == ']');

    // Split on '=' to get attribute and value
    let parts: Vec<&str> = inner.splitn(2, '=').collect();
    if parts.len() != 2 {
        bail!("Invalid attribute selector: {}", selector_str);
    }

    let attr = parts[0].trim();
    let value = parts[1].trim().trim_matches('"');

    let mut selector = Selector::default();

    match attr {
        "text" => selector.text = value.to_string(),
        "resourceId" | "resource_id" => selector.resource_id = value.to_string(),
        "contentDescription" | "content_desc" => selector.content_desc = value.to_string(),
        "className" | "class_name" => selector.class_name = value.to_string(),
        "elementId" | "element_id" => selector.element_id = value.to_string(),
        _ => bail!("Unknown attribute: {}", attr),
    }

    Ok(selector)
}

/// Builder for constructing selectors programmatically
pub struct SelectorBuilder {
    selector: Selector,
}

impl SelectorBuilder {
    /// Create new selector builder
    pub fn new() -> Self {
        Self {
            selector: Selector::default(),
        }
    }

    /// Set text selector
    #[allow(dead_code)]
    pub fn text(mut self, text: impl Into<String>) -> Self {
        self.selector.text = text.into();
        self
    }

    /// Set resource ID selector
    #[allow(dead_code)]
    pub fn resource_id(mut self, id: impl Into<String>) -> Self {
        self.selector.resource_id = id.into();
        self
    }

    /// Set content description selector
    #[allow(dead_code)]
    pub fn content_desc(mut self, desc: impl Into<String>) -> Self {
        self.selector.content_desc = desc.into();
        self
    }

    /// Set class name selector
    #[allow(dead_code)]
    pub fn class_name(mut self, class: impl Into<String>) -> Self {
        self.selector.class_name = class.into();
        self
    }

    /// Set element ID selector
    #[allow(dead_code)]
    pub fn element_id(mut self, id: impl Into<String>) -> Self {
        self.selector.element_id = id.into();
        self
    }

    /// Set exact match flag
    #[allow(dead_code)]
    pub fn exact_match(mut self, exact: bool) -> Self {
        self.selector.exact_match = exact;
        self
    }

    /// Set visible only flag
    #[allow(dead_code)]
    pub fn visible_only(mut self, visible: bool) -> Self {
        self.selector.visible_only = visible;
        self
    }

    /// Set enabled only flag
    #[allow(dead_code)]
    pub fn enabled_only(mut self, enabled: bool) -> Self {
        self.selector.enabled_only = enabled;
        self
    }

    /// Set index for selecting Nth match
    #[allow(dead_code)]
    pub fn index(mut self, index: i32) -> Self {
        self.selector.index = index;
        self
    }

    /// Build the selector
    #[allow(dead_code)]
    pub fn build(self) -> Selector {
        self.selector
    }
}

impl Default for SelectorBuilder {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_text_selector() {
        let selector = parse_selector("\"Login\"").unwrap();
        assert_eq!(selector.text, "Login");
        assert!(!selector.exact_match);
    }

    #[test]
    fn test_parse_id_selector() {
        let selector = parse_selector("#login_button").unwrap();
        assert_eq!(selector.resource_id, "login_button");
    }

    #[test]
    fn test_parse_class_selector() {
        let selector = parse_selector(".Button").unwrap();
        assert_eq!(selector.class_name, "Button");
    }

    #[test]
    fn test_parse_attribute_selector() {
        let selector = parse_selector("[text=\"Login\"]").unwrap();
        assert_eq!(selector.text, "Login");
    }

    #[test]
    fn test_selector_builder() {
        let selector = SelectorBuilder::new()
            .text("Login")
            .visible_only(true)
            .enabled_only(true)
            .build();

        assert_eq!(selector.text, "Login");
        assert!(selector.visible_only);
        assert!(selector.enabled_only);
    }
}
