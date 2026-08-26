package com.loopin.player2.core.content

enum class ContentType { VIDEO, IMAGE, WEATHER, CLOCK, DATE, TEXT, INFORMATION, SPECIAL_EVENT }
enum class ContentPriority(val weight: Int) { LOW(0), NORMAL(1), HIGH(2), CRITICAL(3) }
enum class SpecialEvent { COPA, FESTA_LOCAL, FERIADO, DATAS_COMEMORATIVAS }

data class ContentSchedule(
    val startMinuteOfDay: Int? = null,
    val endMinuteOfDay: Int? = null,
    val daysOfWeek: Set<Int> = emptySet(),
    val event: SpecialEvent? = null,
) {
    init {
        require(startMinuteOfDay == null || startMinuteOfDay in 0..1439)
        require(endMinuteOfDay == null || endMinuteOfDay in 0..1439)
        require(daysOfWeek.all { it in 1..7 })
    }
    fun isActive(at: ContentMoment, activeEvents: Set<SpecialEvent> = emptySet()): Boolean {
        if (daysOfWeek.isNotEmpty() && at.dayOfWeek !in daysOfWeek) return false
        if (event != null && event !in activeEvents) return false
        val start = startMinuteOfDay ?: return true
        val end = endMinuteOfDay ?: return true
        val time = at.minuteOfDay
        return if (start <= end) time >= start && time < end else time >= start || time < end
    }
}

data class ContentMoment(val dayOfWeek: Int, val minuteOfDay: Int) {
    init { require(dayOfWeek in 1..7); require(minuteOfDay in 0..1439) }
}

data class ContentItem(
    val id: String,
    val type: ContentType,
    val priority: ContentPriority = ContentPriority.NORMAL,
    val durationMs: Long? = null,
    val schedule: ContentSchedule = ContentSchedule(),
    val payload: Map<String, String> = emptyMap(),
) { init { require(id.isNotBlank()); require(durationMs == null || durationMs > 0) } }

class ContentScheduler {
    fun select(items: Sequence<ContentItem>, at: ContentMoment, events: Set<SpecialEvent> = emptySet()): List<ContentItem> =
        items.filter { it.schedule.isActive(at, events) }
            .sortedWith(compareByDescending<ContentItem> { it.priority.weight }.thenBy { it.id })
            .toList()
}

interface ContentRenderer { val supportedType: ContentType; fun render(item: ContentItem): RenderResult }
sealed interface RenderResult { data object Rendered : RenderResult; data class Failed(val reason: String) : RenderResult }
interface ContentResolver { fun rendererFor(item: ContentItem): ContentRenderer? }
class RegistryContentResolver(renderers: Iterable<ContentRenderer>) : ContentResolver {
    private val byType = renderers.associateBy { it.supportedType }
    override fun rendererFor(item: ContentItem) = byType[item.type]
}

enum class LayoutTheme { LOOPIN_DEFAULT, LOOPIN_EVENT, LOOPIN_SPORTS, LOOPIN_HOLIDAY }
enum class TransitionType { FADE, CROSSFADE, SLIDE }
enum class ContentOrientation { PORTRAIT, LANDSCAPE, AUTO }
enum class ContentScalePolicy { CENTER_CROP }

data class CanvasSize(val width: Int, val height: Int)

data class ContentPresentation(
    val orientation: ContentOrientation = ContentOrientation.PORTRAIT,
    val scalePolicy: ContentScalePolicy = ContentScalePolicy.CENTER_CROP,
) {
    fun aspectWidth(): Int = if (orientation == ContentOrientation.LANDSCAPE) 16 else 9
    fun aspectHeight(): Int = if (orientation == ContentOrientation.LANDSCAPE) 9 else 16

    fun fitInside(availableWidth: Int, availableHeight: Int): CanvasSize {
        require(availableWidth > 0 && availableHeight > 0)
        val widthByHeight = availableHeight.toLong() * aspectWidth() / aspectHeight()
        return if (widthByHeight <= availableWidth) CanvasSize(widthByHeight.toInt(), availableHeight)
        else CanvasSize(availableWidth, (availableWidth.toLong() * aspectHeight() / aspectWidth()).toInt())
    }

    fun cropScale(sourceWidth: Int, sourceHeight: Int): Double {
        require(sourceWidth > 0 && sourceHeight > 0)
        return maxOf(aspectWidth().toDouble() / sourceWidth, aspectHeight().toDouble() / sourceHeight)
    }
    fun topMarginPx(canvasHeight: Int): Int = (canvasHeight * 0.035f).toInt()
    fun endMarginPx(canvasWidth: Int): Int = (canvasWidth * 0.055f).toInt()
    fun weatherCardWidthPx(canvasWidth: Int): Int = (canvasWidth * 0.84f).toInt()
}
data class TransitionSpec(val type: TransitionType = TransitionType.FADE, val durationMs: Long = 250) {
    init { require(durationMs in 0..1_000) }
}
data class ContentLayout(
    val id: String,
    val primaryContentId: String?,
    val widgetIds: List<String> = emptyList(),
    val theme: LayoutTheme = LayoutTheme.LOOPIN_DEFAULT,
    val transition: TransitionSpec = TransitionSpec(),
)
class LayoutEngine {
    @Volatile private var active: ContentLayout? = null
    fun current() = active
    fun apply(layout: ContentLayout): ContentLayout = layout.also { active = it }
}

data class ImageMemoryPolicy(
    val maxDimensionPx: Int = 1920,
    val maxDecodedBytes: Long = 16L * 1024 * 1024,
    val maxPrefetchedItems: Int = 1,
) {
    init { require(maxDimensionPx in 320..3840); require(maxDecodedBytes > 0); require(maxPrefetchedItems in 0..2) }
    fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > maxDimensionPx || height / sample > maxDimensionPx) sample *= 2
        return sample
    }
}

object ContentLogEvent {
    const val CONTENT_SELECTED = "CONTENT_SELECTED"; const val CONTENT_STARTED = "CONTENT_STARTED"
    const val CONTENT_FINISHED = "CONTENT_FINISHED"; const val WIDGET_STARTED = "WIDGET_STARTED"
    const val WIDGET_FAILED = "WIDGET_FAILED"; const val WEATHER_UPDATED = "WEATHER_UPDATED"
    const val WEATHER_STALE = "WEATHER_STALE"; const val WEATHER_UNAVAILABLE = "WEATHER_UNAVAILABLE"
    const val LAYOUT_CHANGED = "LAYOUT_CHANGED"; const val TRANSITION_STARTED = "TRANSITION_STARTED"
    const val CONTENT_ERROR = "CONTENT_ERROR"
}
