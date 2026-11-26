package com.pocketcode.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch29Test {

    // ---------- AssistMemory ----------

    @Test
    fun memorySaveParse() {
        assertEquals("люблю овсянку" to "овсянку", null) // sanity: pair shape below
        val (k, v) = AssistMemory.parseSave("Запомни: любимый завтрак=овсянка")!!
        assertEquals("любимый завтрак", k)
        assertEquals("овсянка", v)
        // «запомни, ...»
        assertNotNull(AssistMemory.parseSave("запомни, меня зовут=Саша"))
        // «запомни …» без двоеточия и без '=' → null (нужен формат ключ=значение)
        assertNull(AssistMemory.parseSave("запомни что я люблю овсянку"))
        assertNull(AssistMemory.parseSave("привет"))
        assertNull(AssistMemory.parseSave("запомни:"))
    }

    @Test
    fun memoryPutAnswerSerialize() {
        var store = emptyMap<String, String>()
        store = AssistMemory.put(store, "имя", "Саша")
        store = AssistMemory.put(store, "любимый завтрак", "овсянка")
        assertEquals("Саша", AssistMemory.answer("Как меня зовут?", store))
        assertEquals("овсянка", AssistMemory.answer("что мой любимый завтрак?", store))
        assertNull(AssistMemory.answer("какая погода в москве", store))
        // сериализация туда-обратно
        assertEquals(store, AssistMemory.parse(AssistMemory.serialize(store)))
        assertEquals(emptyMap<String, String>(), AssistMemory.parse(""))
        // обновление факта
        store = AssistMemory.put(store, "имя", "Миша")
        assertEquals("Миша", AssistMemory.answer("имя?", store))
    }

    // ---------- AssistCalc ----------

    @Test
    fun calcArithmetic() {
        assertEquals(20.0, AssistCalc.eval("(2+3)*4")!!, 1e-9)
        assertEquals(7.0, AssistCalc.eval("2+2*2+1")!!, 1e-9)
        assertEquals(-4.0, AssistCalc.eval("2-3*2")!!, 1e-9)
        assertEquals(2.5, AssistCalc.eval("5/2")!!, 1e-9)
        assertEquals(3.0, AssistCalc.eval("-3+6")!!, 1e-9)
        assertEquals(1.5, AssistCalc.eval("1,5+0")!!, 1e-9)
        assertNull(AssistCalc.eval("2+"))
        assertNull(AssistCalc.eval("(2+3"))
        assertNull(AssistCalc.eval("abc"))
    }

    @Test
    fun calcPercentAndConvert() {
        assertEquals(312.0, AssistCalc.percent("13% от 2400")!!, 1e-9)
        assertEquals(312.0, AssistCalc.percent("13 % от 2 400".replace(" ", ""))!!, 1e-9)
        assertNull(AssistCalc.percent("13 процентов"))
        assertEquals(15000.0, AssistCalc.convert("150 долларов в рубли", 100.0)!!, 1e-9)
        assertEquals(15000.0, AssistCalc.convert("150 usd to rub", 100.0)!!, 1e-9)
        assertNull(AssistCalc.convert("150 долларов в рубли", null))
        assertNull(AssistCalc.convert("привет", 100.0))
    }

    @Test
    fun calcAnswerRouting() {
        assertEquals("= 312", AssistCalc.answer("13% от 2400"))
        assertEquals("= 15000 ₽", AssistCalc.answer("150 долларов в рубли", 100.0))
        assertEquals("= 20", AssistCalc.answer("(2+3)*4"))
        assertNull(AssistCalc.answer("какая погода в москве"))
        assertNull(AssistCalc.answer("150 долларов в рубли")) // без курса — не отвечаем числом
    }

    @Test
    fun calcIsCalcGate() {
        assertTrue(AssistCalc.isCalc("2+2"))
        assertTrue(AssistCalc.isCalc("13% от 2400"))
        assertTrue(AssistCalc.isCalc("(2+3)*4"))
        assertFalse(AssistCalc.isCalc("2 ноября"))
        assertFalse(AssistCalc.isCalc("позвони маме в 5"))
        assertFalse(AssistCalc.isCalc("какая погода"))
    }

    // ---------- AssistRecipes ----------

    @Test
    fun recipesBuiltinAndCustom() {
        assertEquals(listOf("Погода", "Курс доллара", "Новости"), AssistRecipes.resolve("утро"))
        assertEquals(listOf("Погода", "Курс доллара", "Новости"), AssistRecipes.resolve("рецепт утро"))
        assertEquals(listOf("Мой шаг"), AssistRecipes.resolve("утро", custom = mapOf("утро" to listOf("Мой шаг"))))
        assertNull(AssistRecipes.resolve("вечер"))
        assertNull(AssistRecipes.resolve("какая погода"))
    }
}
