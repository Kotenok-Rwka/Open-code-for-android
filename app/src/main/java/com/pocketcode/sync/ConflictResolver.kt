package com.pocketcode.sync

/** Результат разбора конфликта: вердикт и текст для сохранения. */
data class ConflictResult(val verdict: String, val merged: String)

/**
 * Трёхстороннее слияние правок одного файла с двух устройств.
 * verdict: "same" — тексты совпали, "mine"/"theirs" — изменила только одна сторона,
 * "both" — конфликт, merged содержит обе версии с маркерами.
 */
fun resolveConflict(base: String, mine: String, theirs: String): ConflictResult {
    if (mine == theirs) return ConflictResult("same", mine)
    if (mine == base) return ConflictResult("theirs", theirs)
    if (theirs == base) return ConflictResult("mine", mine)
    val merged = "<<<<<<< mine\n$mine\n=======\n$theirs\n>>>>>>> theirs"
    return ConflictResult("both", merged)
}
