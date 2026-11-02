package app.ocmobile.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import java.util.Calendar

/** What's happening around the mascot: dictation, voice level, a holiday for debugging. */
object MascotSignals {
    var listening by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)
    /** Debug build only: `--es dev_holiday halloween` shows an outfit without waiting for the date. */
    var forced by mutableStateOf<Holiday?>(null)
}

/**
 * A holiday or season: the mascot's outfit and what flies around it.
 * [outfit] is a hat/decoration, [ambient] a particle (f snow, c confetti, b bat, l leaf, h heart),
 * [every] how often it appears, ms.
 */
enum class Holiday(val outfit: List<String>?, val anchor: Float, val ambient: Char?, val every: Long, val sneezes: Boolean = false) {
    NewYear(PARTY_HAT, 360f, 'c', 450),
    Christmas(SANTA_HAT, 360f, 'f', 380),
    Winter(null, 0f, 'f', 1400),
    Valentine(BOW, 200f, 'h', 1600),
    WomensDay(TULIP, 360f, null, 0),
    Patrick(TOP_HAT, 360f, null, 0),
    AprilFools(null, 0f, null, 0),
    Spring(SPROUT, 360f, null, 0),
    Summer(SHADES, 360f, null, 0),
    Halloween(WITCH_HAT, 360f, 'b', 1700),
    Autumn(null, 0f, 'l', 1300, sneezes = true);

    companion object {
        fun now(): Holiday? {
            MascotSignals.forced?.let { return it }
            val c = Calendar.getInstance()
            val m = c.get(Calendar.MONTH) + 1
            val d = c.get(Calendar.DAY_OF_MONTH)
            return when {
                (m == 12 && d == 31) || (m == 1 && d <= 2) -> NewYear
                m == 12 -> Christmas
                m == 2 && d == 14 -> Valentine
                m == 1 || m == 2 -> Winter
                m == 3 && d == 8 -> WomensDay
                m == 3 && d == 17 -> Patrick
                m == 4 && d == 1 -> AprilFools
                m in 3..5 -> Spring
                m in 6..8 -> Summer
                m == 10 && d >= 25 -> Halloween
                m in 9..11 -> Autumn
                else -> null
            }
        }

        fun parse(s: String): Holiday? = entries.firstOrNull { it.name.equals(s, true) }

        /** What's worn now: debug first, then the settings choice (auto by date, off for nothing). */
        fun chosen(): Holiday? = MascotSignals.forced ?: when (val o = app.ocmobile.Graph.prefs.mascotOutfit) {
            "auto" -> now()
            "off" -> null
            else -> parse(o)
        }

        /** Outfits to pick by hand: label → the holiday whose outfit and particles are used. */
        val wardrobe = listOf(
            "Праздничный колпак" to NewYear, "Шапка Санты" to Christmas, "Шляпа ведьмы" to Halloween, "Цилиндр Патрика" to Patrick,
            "Бантик" to Valentine, "Тюльпан" to WomensDay, "Росток" to Spring, "Солнечные очки" to Summer,
        )
    }
}

// outfits: rows from top to bottom, the bottom of the picture sits on top of the cloud
private val PARTY_HAT = listOf("..y..", "..l..", ".lpl.", ".lll.", "lplpl", "lllll")
private val SANTA_HAT = listOf("......ww", ".....rww", "....rrr.", "...rrrr.", "..rrrrr.", ".rrrrrr.", "wwwwwwwww")
private val WITCH_HAT = listOf(".....d...", "....dd...", "...ddd...", "..dddd...", "..llll...", ".dddddd..", "ddddddddd")
private val TOP_HAT = listOf(".gggg.", ".gggg.", ".gggg.", ".kyyk.", "gggggg")
private val BOW = listOf("pp.pp", "ppppp", "pp.pp")
private val TULIP = listOf(".p.p.", "ppppp", "ppppp", ".ppp.", "..g..", ".gg..", "..g..")
private val SPROUT = listOf("gg.gg", ".ggg.", "..g..", "..g..")
private val SHADES = listOf("kkk.kkk", "kkkkkkk", "kkk.kkk")

private val OUTFIT_COLORS = mapOf(
    'r' to Color(0xFFD9575B), 'w' to Color(0xFFF4F4F6), 'l' to Color(0xFF9F9DF2), 'p' to Color(0xFFF08DB0),
    'y' to Color(0xFFF2C94C), 'd' to Color(0xFF3A3150), 'g' to Color(0xFF5DAE6B), 'k' to Color(0xFF18181A),
)

/** Draws an outfit: [x], [y] is the cloud's top left corner, [k] the scale, [step] the cloud's pixel. */
internal fun DrawScope.drawOutfit(h: Holiday, x: Float, y: Float, k: Float, step: Float) {
    val pic = h.outfit ?: return
    val wide = pic.maxOf { it.length }
    val left = x + h.anchor * k - wide * step / 2
    // shades sit on the forehead, everything else on top of the head
    val bottom = if (h == Holiday.Summer) y + step * 2.4f else y + step * 0.4f
    pic.forEachIndexed { r, row ->
        row.forEachIndexed { cI, ch ->
            OUTFIT_COLORS[ch]?.let { drawRect(it, Offset(left + cI * step, bottom - (pic.size - r) * step), Size(step, step)) }
        }
    }
}

/** What the mascot says in its room. */
object CloudTalk {
    val hello = listOf("О, привет!", "Ты зашёл в гости!", "Привет, друг.", "Я как раз летал вокруг.", "Добро пожаловать в мою комнату.")
    val tap = listOf("Буп.", "Хе-хе.", "Щекотно!", "Снова привет!", "Да?", "Я облачко, а не кнопка!", "Ещё раз!", "*подпрыгивает*", "Тык зафиксирован.", "Осторожно, могу пойти дождём.")
    val pet = listOf("Муррр…", "Как приятно.", "Ещё, пожалуйста.", "Ты мне нравишься.", "Лучший человек на свете.", "*радостные звуки облачка*", "Самые нежные поглаживания.")
    val sleep = listOf("Zzz…", "Ещё пять минуточек…", "Снятся синие небеса…")
    val wake = listOf("Я проснулся! Не сплю!", "Я храпел?", "А? Что я пропустил?")
    val angry = listOf("Эй! Хватит!", "Грр.", "Теперь я грозовая туча.", "Грубо.")
    val dizzy = listOf("Ой… всё небо кружится.", "Только не тряси!", "Чувствую себя торнадо.")
    val idle = listOf(
        "Задача ещё выполняется. Сыграем?", "А вы знали, что облака весят около 500 тонн?", "Я бы пустил дождик, но твой телефон не водонепроницаемый.",
        "Во мне 0% воды и 100% пикселей.", "Пинг-понг? Я почти непобедим.", "Как думаешь, коду весело?", "Я считал звёзды. Их очень много.",
    )
    val won = listOf("Ха! Очко мне.", "Слишком медленно!", "Облачко побеждает!", "Попался.")
    val lost = listOf("Случайность.", "Ладно, ладно, твоя взяла.", "Реванш!", "Я просто поддался.")
    fun of(event: String): String? = when (event) {
        "tap" -> tap; "pet" -> pet; "sleep" -> sleep; "wake" -> wake; "angry" -> angry; "dizzy" -> dizzy
        else -> null
    }?.random()

    fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
}
