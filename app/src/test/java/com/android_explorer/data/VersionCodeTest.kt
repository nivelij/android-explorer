package com.android_explorer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCodeTest {
    @Test
    fun `matches the CI formula`() {
        assertEquals(10304, versionCodeOf("v1.3.4"))
        assertEquals(10200, versionCodeOf("1.2.0"))
    }

    @Test
    fun `missing parts count as zero`() {
        assertEquals(10300, versionCodeOf("v1.3"))
        assertEquals(20000, versionCodeOf("v2"))
    }

    @Test
    fun `compares numerically, not as text`() {
        assertTrue(versionCodeOf("v1.10.0")!! > versionCodeOf("v1.9.9")!!)
    }

    @Test
    fun `rejects tags that are not plain versions`() {
        assertNull(versionCodeOf("v1.3.4-beta"))
        assertNull(versionCodeOf("v1.2.3.4"))
        assertNull(versionCodeOf("latest"))
        assertNull(versionCodeOf("v1..2"))
        assertNull(versionCodeOf(""))
    }
}
