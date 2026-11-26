package com.pocketcode.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch29: подтверждение рискованного ввода текста в полях. */
class PhoneTypeRiskyTest {

    private fun node(text: String = "", resId: String = "") = UiNode(
        id = 1, parent = 0, cls = "EditText", text = text, desc = "", resId = resId,
        bounds = PRect(0, 0, 100, 100), clickable = false, longClickable = false,
        scrollable = false, editable = true, checked = null, focused = true, enabled = true,
    )

    @Test
    fun riskyFieldByResIdAndText() {
        // поле сообщения/поиска/оплаты — рискованное
        assertTrue(PhoneSafety.riskyNode(node(resId = "message_input")))
        assertTrue(PhoneSafety.riskyNode(node(resId = "search_query")))
        assertTrue(PhoneSafety.riskyNode(node(text = "Сумма перевода")))
        // обычное поле имени — нет
        assertFalse(PhoneSafety.riskyNode(node(resId = "first_name")))
        assertFalse(PhoneSafety.riskyNode(null))
    }

    @Test
    fun confirmOnlyInRiskyMode() {
        val risky = PhoneSafety.riskyNode(node(resId = "message_input"))
        // RISKY-режим: рискованное поле требует подтверждения, обычное — нет
        assertTrue(PhoneSafety.needsConfirm(PhoneConfirm.RISKY, readOnly = false, risky = risky))
        assertFalse(PhoneSafety.needsConfirm(PhoneConfirm.RISKY, readOnly = false, risky = false))
        // EVERY требует всегда (кроме чтения), чтение — никогда
        assertTrue(PhoneSafety.needsConfirm(PhoneConfirm.EVERY, readOnly = false, risky = false))
        assertFalse(PhoneSafety.needsConfirm(PhoneConfirm.EVERY, readOnly = true, risky = true))
    }

    @Test
    fun parseConfirmMode() {
        assertEquals(PhoneConfirm.RISKY, PhoneConfirm.parse("RISKY"))
        assertEquals(PhoneConfirm.EVERY, PhoneConfirm.parse("мусор"))
        assertEquals(PhoneConfirm.EVERY, PhoneConfirm.parse(null))
    }
}
