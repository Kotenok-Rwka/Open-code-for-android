package com.pocketcode.assist

/** Что сейчас видно в окне ассистента. */
enum class Phase { INPUT, WORKING, OFFER, POPUP, FULLSCREEN, ERROR }

/** Правила переходов между состояниями окна. */
object AssistFlow {
    /**
     * Что показать, когда ответ готов. Если пользователь уточняет вопрос из уже открытого ответа (во всплывающем окне или на весь экран),
     * остаёмся в том же виде и ничего не спрашиваем второй раз.
     */
    fun afterAnswer(mode: ResultMode, followUpFrom: Phase?): Phase {
        if (followUpFrom == Phase.POPUP || followUpFrom == Phase.FULLSCREEN) return followUpFrom
        return when (mode) {
            ResultMode.ASK -> Phase.OFFER
            ResultMode.POPUP -> Phase.POPUP
            ResultMode.FULLSCREEN -> Phase.FULLSCREEN
        }
    }

    /** Присылать ли уведомление: ответ готов, а окно уже закрыто. */
    fun shouldNotify(sessionVisible: Boolean, prefs: AssistPrefs): Boolean = !sessionVisible && prefs.notifyWhenHidden

    /** Всплывать ли окном снова: ответ готов, а окно уже закрыто. */
    fun shouldPopup(sessionVisible: Boolean, prefs: AssistPrefs): Boolean = !sessionVisible && prefs.popupWhenReady

    /** Нужно ли спросить подтверждение перед закрытием: только когда на экране готовый ответ или предложение его показать. */
    fun needsCloseConfirm(phase: Phase, prefs: AssistPrefs): Boolean =
        prefs.confirmClose && (phase == Phase.OFFER || phase == Phase.POPUP || phase == Phase.FULLSCREEN)

    /** Запоминает выбор «здесь» или «на весь экран», если пользователь отметил «запомнить». */
    fun rememberChoice(prefs: AssistPrefs, fullscreen: Boolean, remember: Boolean): AssistPrefs =
        if (!remember) prefs else prefs.copy(resultMode = if (fullscreen) ResultMode.FULLSCREEN else ResultMode.POPUP)

    fun showHeadline(p: ProgressMode): Boolean = p != ProgressMode.MINIMAL

    fun showDetails(p: ProgressMode): Boolean = p == ProgressMode.DETAILED

    /** На заблокированном экране ничего не открываем: приложения и ссылки потребовали бы разблокировки. */
    fun canOpenOutside(locked: Boolean): Boolean = !locked
}
