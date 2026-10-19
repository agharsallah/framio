package dev.framio.meadow.core

/**
 * The five meadow friends. Sprites: assets/sprites/ch_<id>_<pose>.webp (tools/render3d/models/characters.py).
 * Voice ids per friend: <id>_hello, <id>_giggle_1..3 (voice/<lang>/friends.tsv).
 */
enum class Friend(val id: String, val title: Txt, val argb: Long) {
    Pip("pip", Txt("Pip"), 0xFFE7B05A),    // a round honey bird: numbers, puzzles, the guide
    Tuka("tuka", Txt("Tuka"), 0xFFDC8B62), // a curious fox kit: colours, shapes, art
    Bo("bo", Txt("Bo"), 0xFF8FAFD3),       // a gentle elephant: words, music
    Moss("moss", Txt("Moss"), 0xFF8DB48A), // a slow, kind turtle: good habits
    Luma("luma", Txt("Luma"), 0xFF9D97D2); // a sleepy owl: calm, feelings, bedtime

    fun sprite(pose: Pose) = "ch_${id}_${pose.file}"
}

enum class Pose(val file: String) { Idle("idle"), Blink("blink"), Talk("talk"), Happy("happy"), Sleep("sleep"), Wave("wave") }
