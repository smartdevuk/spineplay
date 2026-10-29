package dev.family.audiobooks

fun sleepVolume(deadline: Long?, chapterEnd: Long?, position: Long, speed: Float, now: Long): Float {
    val remaining = listOfNotNull(deadline?.minus(now)?.toFloat(), chapterEnd?.let { (it - position).coerceAtLeast(0) / speed.coerceAtLeast(.5f) }).minOrNull()
        ?: return 1f
    return (remaining / 15000f).coerceIn(0f, 1f)
}
