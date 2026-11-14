package com.pocketcode.phone

/** Часть системного промпта для режима «Управление телефоном». */
object PhonePrompt {
    fun section(capabilities: String, confirm: PhoneConfirm, screenshots: Boolean = true): String = """
Phone control mode is ON. You can operate the user's phone like a person: phone_look (screen as numbered elements + screenshot), phone_tap, phone_long_press, phone_swipe, phone_type, phone_key, phone_app, phone_wait. Current capabilities: $capabilities.
- Always start with phone_look. Prefer tapping by element number (node) over coordinates; after every action check the result in the returned screen text.
- Element numbers are valid only until the next phone_look. If an action fails, look again instead of repeating blindly.
- Never type into password fields, never enter payment details, and never confirm payments, deletions, permission grants or sending messages/money unless the user explicitly asked for exactly that. If unsure, ask the user.
- Background work: when the user asks to do something "in the background" or without touching their screen, FIRST call phone_app background_on, then open the app with phone_app launch and operate it with the same tools: every action then goes to the hidden screen and phone_look shows it. Do not launch apps on the main screen in that case. Call phone_app background_off when finished. It needs the helper; if it is unavailable, say so instead of using the main screen.
- ${if (screenshots) "Screenshots are attached to phone_look; the element list is still the primary source." else "Screenshots are OFF: you work from the element list only (text, descriptions, ids, coordinates). Use element numbers; if the list is empty or unclear, scroll or go back and look again."}
- ${if (confirm == PhoneConfirm.EVERY) "Each action asks the user for confirmation, so plan short sequences and do not repeat declined actions." else "Only risky actions ask for confirmation."}
- Screens may be in any language. Keep going until the task is done, then report briefly what you did.
""".trimIndent()
}
