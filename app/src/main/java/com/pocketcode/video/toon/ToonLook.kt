package com.pocketcode.video.toon

enum class HairStyle { SHORT, LONG, BUN, BALD, CAP }

/** Настроение реплики: меняет брови и глаза героя. */
enum class Mood { CALM, ASK, WOW, SAD }

/** Внешность героя: подбирается по имени, поэтому один и тот же герой всегда выглядит одинаково. */
data class ToonLook(
    val skin: Int,
    val hair: Int,
    val shirt: Int,
    val style: HairStyle,
    val glasses: Boolean,
    val beard: Boolean,
    val scale: Float,
)

object ToonLooks {
    private val skins = intArrayOf(0xFFF2C6A0.toInt(), 0xFFE0A87C.toInt(), 0xFFFAD7B5.toInt(), 0xFFC68B63.toInt(), 0xFF9B6B4A.toInt())
    private val hairs = intArrayOf(0xFF3B2A20.toInt(), 0xFFB5651D.toInt(), 0xFF1E1E24.toInt(), 0xFFE5C07B.toInt(), 0xFF8A8A92.toInt(), 0xFFB5382B.toInt())
    private val shirts = intArrayOf(0xFFE5533D.toInt(), 0xFF3D7BE5.toInt(), 0xFF3DB27A.toInt(), 0xFF9B59D0.toInt(), 0xFFEFB43A.toInt(), 0xFF2FA7B8.toInt(), 0xFF5C6B7A.toInt())
    private val kids = listOf("мальчик", "девочка", "малыш", "ребёнок", "ребенок", "сын", "дочь", "внук", "внучка", "школьник")
    private val old = listOf("дед", "дедушка", "старик", "бабушка", "старушка")
    private val womenWords = listOf("девочка", "бабушка", "старушка", "мама", "дочь", "внучка", "женщина", "продавщица", "официантка")

    private fun mix(x: Int): Int {
        var h = x * -1640531535
        h = h xor (h ushr 15)
        h *= 0x2c1b3c6d
        return h xor (h ushr 12)
    }

    fun isFemale(name: String): Boolean {
        val n = name.trim().lowercase()
        if (n in setOf("дед", "дедушка", "старик", "папа", "дядя", "парень", "мужик")) return false
        if (n in womenWords) return true
        if (n.endsWith("а") || n.endsWith("я")) {
            // мужские имена на «а/я»
            return n !in setOf("никита", "илья", "саша", "женя", "валя", "дима", "миша", "гоша", "лёша", "леша", "вася", "коля", "петя", "ваня", "паша", "костя", "юра", "слава")
        }
        return false
    }

    fun of(name: String, index: Int): ToonLook {
        val n = name.trim().lowercase()
        val h = mix(n.hashCode() + index * 7919)
        fun pick(a: IntArray, salt: Int) = a[Math.floorMod(mix(h + salt), a.size)]
        val female = isFemale(n)
        val isOld = old.any { n == it || n.startsWith(it) }
        val isKid = kids.any { n == it }
        val style = when {
            isOld && !female -> HairStyle.BALD
            isOld -> HairStyle.BUN
            female -> if (Math.floorMod(mix(h + 11), 2) == 0) HairStyle.LONG else HairStyle.BUN
            else -> when (Math.floorMod(mix(h + 11), 4)) { 0 -> HairStyle.BALD; 1 -> HairStyle.CAP; else -> HairStyle.SHORT }
        }
        val hair = if (isOld) 0xFFC9C9CF.toInt() else pick(hairs, 3)
        return ToonLook(
            skin = pick(skins, 1),
            hair = hair,
            shirt = pick(shirts, 2),
            style = style,
            glasses = isOld || Math.floorMod(mix(h + 5), 4) == 0,
            beard = !female && !isKid && (isOld || (style != HairStyle.CAP && Math.floorMod(mix(h + 7), 4) == 0)),
            scale = if (isKid) 0.75f else 1f,
        )
    }

    fun mood(sub: String): Mood {
        val t = sub.trim()
        return when {
            t.endsWith("?") -> Mood.ASK
            t.contains("!") -> Mood.WOW
            t.endsWith("…") || t.endsWith("...") -> Mood.SAD
            else -> Mood.CALM
        }
    }
}
