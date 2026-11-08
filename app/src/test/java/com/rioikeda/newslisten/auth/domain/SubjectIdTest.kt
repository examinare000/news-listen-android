package com.rioikeda.newslisten.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubjectIdTest {

    @Test
    fun `android-dec-85 SubjectIdのofは英数字とハイフンとアンダースコアの全一致だけを通し不正はnullにする`() {
        val cases: List<Pair<String?, String?>> = listOf(
            "abc123_-XYZ" to "abc123_-XYZ",
            "u1" to "u1",
            "" to null,
            "a b" to null,
            "a/b" to null,
            "a.b" to null,
            "../evil" to null,
            "u1\n" to null,
            "日本語" to null,
            null to null,
        )

        cases.forEach { (input, expected) ->
            assertEquals("raw=$input", expected, SubjectId.of(input)?.value)
        }
    }

    @Test
    fun `android-dec-85 SubjectIdのofは欠落と不正をどちらもnullにして区別しない`() {
        assertNull(SubjectId.of(null))
        assertNull(SubjectId.of("a.b"))
        assertEquals(SubjectId.of(null), SubjectId.of("a.b"))
    }
}
