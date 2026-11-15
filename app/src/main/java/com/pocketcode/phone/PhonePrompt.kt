package com.pocketcode.phone

/** Часть системного промпта для режима «Управление телефоном». */
object PhonePrompt {
    fun section(capabilities: String, confirm: PhoneConfirm, screenshots: Boolean = true, background: Boolean = true): String = """
Phone control mode is ON. You can operate the user's phone like a person: phone_look (screen as numbered elements + screenshot), phone_tap, phone_long_press, phone_swipe, phone_type, phone_set_wheel, phone_key, phone_app, phone_wait. Current capabilities: $capabilities.
- Always start with phone_look. Prefer tapping by element number (node) over coordinates; after every action check the result in the returned screen text.
- Wheel pickers (time, date, region, numbers): do not swipe them by hand; call phone_set_wheel with the wheel (or one of its rows) as node and the target value as written on it. If the element list has almost no text, phone_look adds text recognized from the screenshot (Latin and Chinese only, not Cyrillic).
- Element numbers are valid only until the next phone_look. If an action fails, look again instead of repeating blindly.
- Never type into password fields, never enter payment details, and never confirm payments, deletions, permission grants or sending messages/money unless the user explicitly asked for exactly that. If unsure, ask the user.
- ${if (background) "Background screen is the DEFAULT whenever the helper is available: phone_app launch opens the app on a hidden screen that does not disturb the user, and every following action goes there and phone_look shows it. Do not call phone_app launch with background=false and do not use the main screen unless the user explicitly asks to see it or to do it on their screen. Call phone_app background_off when finished. Without the helper apps open on the main screen; say so to the user." else "The user chose to work on the MAIN screen: phone_app launch opens apps on the main screen where the user watches. Use the hidden screen (phone_app background_on, then launch) only if the user explicitly asks to work in the background."} If an app fails to open, report the exact error to the user; never work around it with shell or root commands. If an app is already open on the main screen and cannot start on the hidden one, ask the user whether to move it to the hidden screen (phone_app move_to_background), close it on the main screen and reopen, or work on the main screen."
- ${if (screenshots) "Screenshots are attached to phone_look; the element list is still the primary source." else "Screenshots are OFF: you work from the element list only (text, descriptions, ids, coordinates). Use element numbers; if the list is empty or unclear, scroll or go back and look again."}
- ${if (confirm == PhoneConfirm.EVERY) "Each action asks the user for confirmation, so plan short sequences and do not repeat declined actions." else "Only risky actions ask for confirmation."}
- Screens may be in any language. Keep going until the task is done, then report briefly what you did.
""".trimIndent()
}
