package org.multipaz.idv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IcaoCountryCodesTest {
    @Test
    fun mapsNzlAndAus() {
        assertEquals("NZ", IcaoCountryCodes.toAlpha2("NZL"))
        assertEquals("AU", IcaoCountryCodes.toAlpha2("AUS"))
    }

    @Test
    fun mapsValidatopia() {
        assertEquals("XV", IcaoCountryCodes.toAlpha2("XVA"))
        assertEquals(IcaoCountryCodes.VALIDATOPIA_ALPHA_2, IcaoCountryCodes.toAlpha2(IcaoCountryCodes.VALIDATOPIA_ALPHA_3))
    }

    @Test
    fun isCaseInsensitive() {
        assertEquals("NZ", IcaoCountryCodes.toAlpha2("nzl"))
    }

    @Test
    fun returnsNullForUnknownCode() {
        assertNull(IcaoCountryCodes.toAlpha2("UNK"))
        assertNull(IcaoCountryCodes.toAlpha2("ZZZ"))
    }
}
