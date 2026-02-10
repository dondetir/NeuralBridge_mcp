/*!
 * Element Resolver
 *
 * Resolves selectors to UI elements using fuzzy matching, semantic type
 * classification, and intelligent prioritization.
 *
 * Matching algorithm priority:
 * 1. Exact element ID match
 * 2. Resource ID suffix match
 * 3. Exact text match
 * 4. Partial text match (case-insensitive)
 * 5. Content description match
 * 6. Class name match
 * 7. Fuzzy text match (Levenshtein distance < 3)
 */

use anyhow::{Result, bail};
use tracing::{debug, trace};

use crate::protocol::pb::{UiElement, Selector};

/// Element resolver with intelligent matching
#[allow(dead_code)]
pub struct ElementResolver {
    /// UI tree cache for fast lookups
    elements: Vec<UiElement>,
}

impl ElementResolver {
    /// Create new resolver with UI tree
    #[allow(dead_code)]
    pub fn new(elements: Vec<UiElement>) -> Self {
        Self { elements }
    }

    /// Resolve selector to matching elements
    ///
    /// # Arguments
    /// * `selector` - Selector criteria
    /// * `find_all` - Return all matches (default: false, returns best match)
    ///
    /// # Returns
    /// Vector of matching UiElement objects, prioritized by match quality
    #[allow(dead_code)]
    pub fn resolve(&self, selector: &Selector, find_all: bool) -> Result<Vec<UiElement>> {
        debug!("Resolving selector: text={:?}, id={:?}, class={:?}",
            selector.text, selector.resource_id, selector.class_name);

        let mut matches = Vec::new();

        // Strategy 1: Direct element ID lookup
        if !selector.element_id.is_empty() {
            if let Some(element) = self.find_by_element_id(&selector.element_id) {
                matches.push((100, element.clone())); // Score: 100 (perfect match)
            }
        }

        // Strategy 2: Resource ID match
        if !selector.resource_id.is_empty() {
            for element in &self.elements {
                if self.match_resource_id(&element.resource_id, &selector.resource_id) {
                    matches.push((90, element.clone())); // Score: 90
                }
            }
        }

        // Strategy 3: Text matching
        if !selector.text.is_empty() {
            for element in &self.elements {
                if let Some(score) = self.match_text(&element.text, &selector.text, selector.exact_match) {
                    matches.push((score, element.clone()));
                }
            }
        }

        // Strategy 4: Content description match
        if !selector.content_desc.is_empty() {
            for element in &self.elements {
                if self.match_content_desc(&element.content_description, &selector.content_desc) {
                    matches.push((80, element.clone())); // Score: 80
                }
            }
        }

        // Strategy 5: Class name match
        if !selector.class_name.is_empty() {
            for element in &self.elements {
                if self.match_class_name(&element.class_name, &selector.class_name) {
                    matches.push((70, element.clone())); // Score: 70
                }
            }
        }

        // Filter by visibility and enabled state
        if selector.visible_only {
            matches.retain(|(_, e)| e.visible);
        }

        if selector.enabled_only {
            matches.retain(|(_, e)| e.enabled);
        }

        // Remove duplicates (same element matched by multiple criteria)
        matches.sort_by(|a, b| b.0.cmp(&a.0)); // Sort by score descending
        matches.dedup_by(|a, b| a.1.element_id == b.1.element_id);

        // Extract elements
        let mut result: Vec<UiElement> = matches.into_iter().map(|(_, e)| e).collect();

        if result.is_empty() {
            bail!("No elements found matching selector");
        }

        // Apply index selection if specified
        if selector.index >= 0 {
            let index = selector.index as usize;
            if index >= result.len() {
                bail!("Index {} out of range (only {} matches)", index, result.len());
            }
            result = vec![result[index].clone()];
        } else if !find_all {
            // Return best match only
            result = vec![result[0].clone()];
        }

        trace!("Resolved to {} element(s)", result.len());
        Ok(result)
    }

    /// Find element by element ID (exact match)
    #[allow(dead_code)]
    fn find_by_element_id(&self, element_id: &str) -> Option<&UiElement> {
        self.elements.iter().find(|e| e.element_id == element_id)
    }

    /// Match resource ID (suffix match)
    ///
    /// Example: selector "login_button" matches "com.app:id/login_button"
    fn match_resource_id(&self, resource_id: &str, selector_id: &str) -> bool {
        if resource_id.is_empty() {
            return false;
        }

        resource_id == selector_id || resource_id.ends_with(&format!("/{}", selector_id))
    }

    /// Match text with scoring
    ///
    /// Returns Some(score) if match, None otherwise
    fn match_text(&self, text: &str, selector_text: &str, exact_match: bool) -> Option<u32> {
        if text.is_empty() {
            return None;
        }

        if exact_match {
            if text == selector_text {
                Some(95) // Exact match
            } else {
                None
            }
        } else {
            // Case-insensitive partial match
            let text_lower = text.to_lowercase();
            let selector_lower = selector_text.to_lowercase();

            if text_lower == selector_lower {
                Some(95) // Exact match (case-insensitive)
            } else if text_lower.contains(&selector_lower) {
                Some(85) // Partial match
            } else {
                // Fuzzy match using Levenshtein distance
                let distance = Self::levenshtein_distance(&text_lower, &selector_lower);
                if distance < 3 {
                    Some(75 - (distance as u32 * 5)) // Score decreases with distance
                } else {
                    None
                }
            }
        }
    }

    /// Match content description
    #[allow(dead_code)]
    fn match_content_desc(&self, content_desc: &str, selector_desc: &str) -> bool {
        if content_desc.is_empty() {
            return false;
        }

        content_desc.to_lowercase().contains(&selector_desc.to_lowercase())
    }

    /// Match class name (suffix match)
    #[allow(dead_code)]
    fn match_class_name(&self, class_name: &str, selector_class: &str) -> bool {
        if class_name.is_empty() {
            return false;
        }

        class_name == selector_class || class_name.ends_with(&format!(".{}", selector_class))
    }

    /// Calculate Levenshtein distance between two strings
    fn levenshtein_distance(s1: &str, s2: &str) -> usize {
        let len1 = s1.chars().count();
        let len2 = s2.chars().count();

        if len1 == 0 {
            return len2;
        }
        if len2 == 0 {
            return len1;
        }

        let mut matrix = vec![vec![0; len2 + 1]; len1 + 1];

        for (i, row) in matrix.iter_mut().enumerate().take(len1 + 1) {
            row[0] = i;
        }
        #[allow(clippy::needless_range_loop)]
        for j in 0..=len2 {
            matrix[0][j] = j;
        }

        let s1_chars: Vec<char> = s1.chars().collect();
        let s2_chars: Vec<char> = s2.chars().collect();

        for i in 1..=len1 {
            for j in 1..=len2 {
                let cost = if s1_chars[i - 1] == s2_chars[j - 1] { 0 } else { 1 };
                matrix[i][j] = std::cmp::min(
                    std::cmp::min(matrix[i - 1][j] + 1, matrix[i][j - 1] + 1),
                    matrix[i - 1][j - 1] + cost,
                );
            }
        }

        matrix[len1][len2]
    }

    /// Get element at coordinates
    #[allow(dead_code)]
    pub fn element_at_point(&self, x: i32, y: i32) -> Option<UiElement> {
        // Find all elements containing point
        let mut candidates: Vec<&UiElement> = self.elements.iter()
            .filter(|e| {
                if let Some(bounds) = &e.bounds {
                    x >= bounds.left && x <= bounds.right &&
                    y >= bounds.top && y <= bounds.bottom
                } else {
                    false
                }
            })
            .collect();

        if candidates.is_empty() {
            return None;
        }

        // Sort by depth (deepest first) to get most specific element
        candidates.sort_by(|a, b| b.depth.cmp(&a.depth));

        Some(candidates[0].clone())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_levenshtein_distance() {
        assert_eq!(ElementResolver::levenshtein_distance("kitten", "sitting"), 3);
        assert_eq!(ElementResolver::levenshtein_distance("hello", "hello"), 0);
        assert_eq!(ElementResolver::levenshtein_distance("", "test"), 4);
    }

    #[test]
    fn test_match_resource_id() {
        let resolver = ElementResolver::new(vec![]);

        assert!(resolver.match_resource_id("com.app:id/login_button", "login_button"));
        assert!(resolver.match_resource_id("com.app:id/login_button", "com.app:id/login_button"));
        assert!(!resolver.match_resource_id("com.app:id/other_button", "login_button"));
    }

    #[test]
    fn test_match_text() {
        let resolver = ElementResolver::new(vec![]);

        // Exact match
        assert_eq!(resolver.match_text("Login", "Login", true), Some(95));
        assert_eq!(resolver.match_text("Login", "login", true), None);

        // Partial match
        assert_eq!(resolver.match_text("Click to Login", "login", false), Some(85));

        // Case-insensitive exact
        assert_eq!(resolver.match_text("Login", "login", false), Some(95));
    }
}
