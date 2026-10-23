package org.trackertv.player.ui

/** Russian plural: plural(5, "раздача", "раздачи", "раздач") -> "5 раздач". */
fun plural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
    return "$count $word"
}

fun remainingLabel(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return when {
        minutes < 1 -> "осталось меньше минуты"
        minutes < 60 -> "осталось ${plural(minutes, "минута", "минуты", "минут")}"
        else -> "осталось ${minutes / 60} ч ${minutes % 60} мин"
    }
}

fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f ГБ", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} МБ"
    else -> "${bytes shr 10} КБ"
}
