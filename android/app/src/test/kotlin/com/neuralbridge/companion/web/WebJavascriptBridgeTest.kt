package com.neuralbridge.companion.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unit tests for the pure JS/DOM string machinery behind the web_* tools
 * ([WebViewController.jsString], [WebViewController.buildQueryScript],
 * [WebViewController.decodeJsString]).
 *
 * These pin the two things that LOOK like "web_* is broken" but are almost
 * always either a selector-matching-nothing or a decode/corrupt failure:
 *
 *  1. A CSS selector containing quotes/backslashes/newlines must be embedded so
 *     the generated JS stays a single well-formed string literal -- otherwise
 *     `web_query("a[data-x=\"y\"]")` produces rejected JS and returns `[]`
 *     for no good reason.
 *  2. `web_query` returning `[]` (or `web_eval_js` returning `null`) for a
 *     selector that matches no nodes is CORRECT behaviour, not a failure.
 *     The WebView answer `"[]"` decodes back to `[]` and must stay `[]`.
 *
 * Regression context: a live check against https://example.com showed
 * `web_query("h1")` returning [] but the page's `<p>` content readable. That
 * is exactly the *expected* result -- the served example.com page has no <h1>;
 * the DOM tools were correct. This file makes that contract durable so a future
 * "h1 not found" report is recognised as a selector/data question, not a bug in
 * the bridge.
 */
class WebJavascriptBridgeTest {

    // ----------------------------------------------------------------------
    // jsString — safe embedding of arbitrary strings into JS literals
    // ----------------------------------------------------------------------

    @Test
    fun `jsString wraps plain text in quotes`() {
        assertEquals("\"plain\"", WebViewController.jsString("plain"))
    }

    @Test
    fun `jsString escapes double quotes`() {
        assertEquals("\"a\\\"b\"", WebViewController.jsString("a\"b"))
    }

    @Test
    fun `jsString escapes backslash`() {
        assertEquals("\"a\\\\b\"", WebViewController.jsString("a\\b"))
    }

    @Test
    fun `jsString escapes control chars newline and tab`() {
        assertEquals("\"l\\nn\\rt\\tb\"", WebViewController.jsString("l\nn\rt\tb"))
    }

    @Test
    fun `jsString escapes JS line separators U2028 and U2029`() {
        // U+2028/U+2029 are the only chars that terminate a JS string literal,
        // so they must become \u escapes or the whole evaluateJavascript call dies.
        val s = "a\u2028b\u2029c"
        val escaped = WebViewController.jsString(s)
        assertTrue("expected \\u2028 escape", escaped.contains("\\u2028"))
        assertTrue("expected \\u2029 escape", escaped.contains("\\u2029"))
    }

    // ----------------------------------------------------------------------
    // buildQueryScript — the exact JS web_query fires
    // ----------------------------------------------------------------------

    @Test
    fun `query script uses querySelector for single match`() {
        val script = WebViewController.buildQueryScript("h1", all = false)
        assertTrue(script.contains("document.querySelector(sel)"))
        assertTrue(!script.contains("querySelectorAll"))
        assertTrue(script.contains("var sel = \"h1\";"))
    }

    @Test
    fun `query script uses querySelectorAll for all matches`() {
        val script = WebViewController.buildQueryScript("p", all = true)
        assertTrue(script.contains("document.querySelectorAll(sel)"))
        assertTrue(script.contains("var sel = \"p\";"))
    }

    @Test
    fun `query script embeds the selector via jsString so quotes cannot break it`() {
        // The selector mimics the class of value that used to tempt "h1 found nothing".
        // It carries an attribute quote, a single-quote, and a backslash -- the three
        // characters that most easily break out of a hand-built JS string literal.
        val sel = "a[data-x=\"o'b\\\\c\"]"
        val script = WebViewController.buildQueryScript(sel, all = false)
        val embedded = WebViewController.jsString(sel)
        // The assembled script embeds the escaped literal verbatim.
        assertTrue("script should embed escaped selector", script.contains("var sel = $embedded;"))
        // jsString output must itself be a valid JSON string literal, so a real JSON
        // parser round-trips it back to the exact original selector. If quotes or
        // backslashes were not escaped, this parse would throw or return the wrong
        // string -- which is the failure mode that looks like "web_query found nothing".
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(embedded)
            .jsonPrimitive.content
        assertEquals(sel, parsed)
    }

    // ----------------------------------------------------------------------
    // decodeJsString — unwrapping the WebView's JSON-encoded answer
    // ----------------------------------------------------------------------

    @Test
    fun `decodeJsString null literal becomes null`() {
        assertNull(WebViewController.decodeJsString("null"))
    }

    @Test
    fun `decodeJsString unwraps a quoted string`() {
        assertEquals("hello", WebViewController.decodeJsString("\"hello\""))
    }

    @Test
    fun `decodeJsString handles escaped quote`() {
        assertEquals("a\"b", WebViewController.decodeJsString("\"a\\\"b\""))
    }

    @Test
    fun `decodeJsString handles unicode escape`() {
        assertEquals("П", WebViewController.decodeJsString("\"\\u041f\""))
    }

    @Test
    fun `decodeJsString leaves non-string JSON verbatim`() {
        assertEquals("42", WebViewController.decodeJsString("42"))
        assertEquals("true", WebViewController.decodeJsString("true"))
    }

    @Test
    fun `decodeJsString keeps empty-array result as EMPTY not null`() {
        // web_query with a selector that matches nothing:
        //   - WebView evaluates the query script → the string "[]"
        //   - WebView wraps THAT as a JSON string literal → "\"[]\""
        //   - decodeJsString must unwrap to "[]", NOT null and NOT an error.
        // This is the contract behind "h1 not found".
        assertEquals("[]", WebViewController.decodeJsString("\"[]\""))
        assertEquals("[]", WebViewController.decodeJsString("[]"))
    }
}