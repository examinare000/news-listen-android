package com.rioikeda.newslisten.settings.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 上限 0 = 無制限の規則（android-check-04）の正本 [GenerationQuota.of] の検証。 */
class GenerationQuotaTest {

    @Test
    fun `上限0は無制限でremainingを捨てる`() {
        assertEquals(GenerationQuota.Unlimited(10), GenerationQuota.of(0, 10, null))
        assertEquals(GenerationQuota.Unlimited(10), GenerationQuota.of(0, 10, 99))
    }

    @Test
    fun `上限が正ならremainingをそのまま持つ`() {
        assertEquals(GenerationQuota.Limited(5, 2, 3), GenerationQuota.of(5, 2, 3))
    }

    @Test
    fun `上限が正でremainingがnullなら0になる`() {
        assertEquals(GenerationQuota.Limited(5, 5, 0), GenerationQuota.of(5, 5, null))
    }

    @Test
    fun `Limitedは上限0を受け付けない`() {
        assertThrows(IllegalArgumentException::class.java) { GenerationQuota.Limited(0, 0, 0) }
    }

    @Test
    fun `負の上限は例外になる`() {
        assertThrows(IllegalArgumentException::class.java) { GenerationQuota.of(-1, 0, null) }
    }
}
