package org.trackertv.player.data

/**
 * Ways to thank the author. Fill in the values you use; empty ones are hidden, and when all are
 * empty the "Поддержать автора" entries disappear from the app.
 */
object Support {
    /** Monobank jar ("Банка"), e.g. https://send.monobank.ua/jar/XXXXXXXXXX — any Visa/Mastercard, Apple Pay, Google Pay. */
    const val MONOBANK_JAR = "https://send.monobank.ua/jar/9u1tP5JT21"

    /** Card number of the jar, shown as text and QR, digits only. Optional. */
    const val CARD = "4874100034859564"

    /** USDT wallet in the TRON network (TRC-20), starts with "T". Works from any country. */
    const val USDT_TRC20 = ""

    /** TON wallet (Telegram Wallet / Tonkeeper), starts with "UQ" or "EQ". */
    const val TON = ""

    data class Method(val title: String, val detail: String, val qr: String, val shown: String)

    val methods: List<Method>
        get() = buildList {
            if (MONOBANK_JAR.isNotBlank()) add(Method("Monobank · банка", "Гривна, доллар или евро: любая карта, Apple Pay, Google Pay", MONOBANK_JAR, MONOBANK_JAR.removePrefix("https://")))
            if (CARD.isNotBlank()) add(Method("Карта банки", "Пополнение банки по номеру карты из приложения любого банка", CARD, CARD.chunked(4).joinToString(" ")))
            if (USDT_TRC20.isNotBlank()) add(Method("USDT · TRC-20", "Криптовалюта, из любой страны. Только сеть TRON", USDT_TRC20, USDT_TRC20))
            if (TON.isNotBlank()) add(Method("TON", "Telegram Wallet, Tonkeeper", "ton://transfer/$TON", TON))
        }

    val available: Boolean get() = methods.isNotEmpty()

    /** The single gentle reminder appears after this much watching. */
    const val REMINDER_AFTER_MINUTES = 600
}
