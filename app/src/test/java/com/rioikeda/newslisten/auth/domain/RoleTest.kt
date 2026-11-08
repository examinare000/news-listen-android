package com.rioikeda.newslisten.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleTest {

    @Test
    fun `android-dec-85 Roleのofはadminだけをadminにする`() {
        assertEquals(Role.Admin, Role.of("admin"))
        assertTrue(Role.of("admin").isAdmin)
    }

    @Test
    fun `android-dec-85 Roleのofは大文字や未知の値や空を非adminに倒す`() {
        listOf("ADMIN", "user", "member", "", "administrator").forEach { raw ->
            assertEquals("raw=$raw", Role.User, Role.of(raw))
            assertFalse("raw=$raw", Role.of(raw).isAdmin)
        }
    }
}
