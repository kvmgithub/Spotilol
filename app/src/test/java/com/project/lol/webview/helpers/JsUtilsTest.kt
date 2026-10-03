package com.project.lol.webview.helpers

import org.junit.Assert.*
import org.junit.Test

class JsUtilsTest {
    @Test(timeout = 3000) fun scansLargeSingleQuotePayloadWithoutRepeatedSuffixSearches() {
        val code = "const x='value';\n".repeat(100_000) + "console.log('secret');"
        val actual = JsUtils.stripConsoleLogs(code)
        assertTrue(actual.endsWith("void 0;"))
        assertEquals(code.length - "console.log('secret')".length + "void 0".length, actual.length)
    }

    @Test fun preservesLiteralsAndStripsNestedCalls() {
        val code = "const a='console.log(x)'; /* console.log(y) */ console.log(f(g(1))); window.console.log(2);"
        assertEquals("const a='console.log(x)'; /* console.log(y) */ void 0; window.console.log(2);", JsUtils.stripConsoleLogs(code))
    }
}
