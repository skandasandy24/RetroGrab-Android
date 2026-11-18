package com.retrograb.mobile

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * "Pop" look: light grey background with blue dots and sparkles, cards with thick navy outlines
 * and hard offset shadows, chunky Unbounded headings, pill buttons in the chosen accent colour.
 * Visual only - every screen keeps its exact behaviour.
 */

internal val Unbounded = FontFamily(
    Font(R.font.unbounded_semibold, FontWeight.SemiBold),
    Font(R.font.unbounded_extrabold, FontWeight.ExtraBold)
)
internal val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_medium, FontWeight.Normal),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
    Font(R.font.space_grotesk_bold, FontWeight.SemiBold)
)

@Immutable
internal data class PopColors(
    val ink: Color,          // main text (light mode: also the outlines)
    val onInk: Color,        // text on ink
    val line: Color,         // bold outlines on cards, buttons, nav
    val fieldLine: Color,    // quieter outlines on text fields and switches
    val headShadow: Color,   // shadow under the outlined headline word
    val card: Color,
    val pink: Color,         // main accent (buttons, selected tab, highlight words)
    val onPink: Color,
    val lavender: Color,     // soft accent (chips, badges)
    val blue: Color,         // dots, sparkles
    val shadow: Color,       // hard offset shadow
    val bgTop: Color,
    val bgBottom: Color,
    val muted: Color,
    val dark: Boolean
)

// ---------------------------------------------------------------- accents

/** An accent: main fill (buttons, selected tab), soft tint (chips) and sparkle/dot colour. */
@Immutable
internal data class Accent(val name: String, val fill: Color, val soft: Color, val sparkle: Color)

internal const val ACCENT_SETTING = "accent"
internal const val ACCENT_HUE_SETTING = "accentHue"
internal const val ACCENT_CUSTOM = "Custom"
internal const val DEFAULT_ACCENT_HUE = 330f

internal val ACCENTS = listOf(
    Accent("Blue", Color(0xFF9EBCFF), Color(0xFFDDE6FA), Color(0xFF2F5BF0)),
    Accent("Lilac", Color(0xFFC4B0FF), Color(0xFFE8E0FB), Color(0xFF6A3DF0)),
    Accent("Mint", Color(0xFF8EE3C2), Color(0xFFDDF4EA), Color(0xFF0E9E6E)),
    Accent("Peach", Color(0xFFFFBE98), Color(0xFFFBE6DA), Color(0xFFF0622F)),
    Accent("Lemon", Color(0xFFFFE07A), Color(0xFFFAF1CF), Color(0xFFD99A00)),
    Accent("Pink", Color(0xFFF6B3EC), Color(0xFFF7E1F3), Color(0xFFD63AB8))
)

/** Pastel accent from any hue on the colour spectrum. */
internal fun customAccent(hue: Float) = Accent(
    ACCENT_CUSTOM,
    fill = Color.hsv(hue.mod(360f), 0.40f, 1f),
    soft = Color.hsv(hue.mod(360f), 0.12f, 0.98f),
    sparkle = Color.hsv(hue.mod(360f), 0.82f, 0.88f)
)

internal fun accentFor(name: String?, hue: Float?): Accent =
    if (name == ACCENT_CUSTOM) customAccent(hue ?: DEFAULT_ACCENT_HUE)
    else ACCENTS.firstOrNull { it.name == name } ?: ACCENTS.first()

private val lightBase = PopColors(
    ink = Color(0xFF1B2459), onInk = Color.White,
    line = Color(0xFF1B2459), fieldLine = Color(0xFF1B2459), headShadow = Color(0xFF1B2459),
    card = Color(0xFFF6F7FA),
    pink = Color(0xFF9EBCFF), onPink = Color(0xFF1B2459), lavender = Color(0xFFDDE6FA),
    blue = Color(0xFF2F5BF0), shadow = Color(0xFF1B2459),
    bgTop = Color(0xFFE9ECF2), bgBottom = Color(0xFFDCE1EA), muted = Color(0xFF5A6380), dark = false
)
private val darkBase = PopColors(
    ink = Color(0xFFEEF1FA), onInk = Color(0xFF0F121C),
    line = Color(0xFF05070D), fieldLine = Color(0xFF465072), headShadow = Color(0xFF05070D),
    card = Color(0xFF1C2133),
    pink = Color(0xFF9EBCFF), onPink = Color(0xFF0B0E17), lavender = Color(0xFF2A3350),
    blue = Color(0xFF7FA2FF), shadow = Color(0xFF9EBCFF),
    bgTop = Color(0xFF0F121C), bgBottom = Color(0xFF151A28), muted = Color(0xFF9AA3BD), dark = true
)

internal val LocalPop = staticCompositionLocalOf { lightBase }

/** Pop colours for the chosen accent; "Use wallpaper colors" overrides the accent. */
internal fun popColors(dark: Boolean, wallpaper: ColorScheme?, accent: Accent = ACCENTS.first()): PopColors {
    val base = if (dark) darkBase else lightBase
    if (wallpaper != null) return base.copy(
        pink = wallpaper.primaryContainer, onPink = wallpaper.onPrimaryContainer,
        lavender = wallpaper.secondaryContainer, blue = wallpaper.primary
    )
    return if (dark) base.copy(
        pink = accent.fill,
        onPink = base.onPink,
        lavender = lerp(base.card, accent.fill, 0.24f),
        blue = lerp(accent.fill, Color.White, 0.1f),
        shadow = accent.fill,                       // the accent-coloured hard shadow
        headShadow = lerp(accent.sparkle, Color.Black, 0.15f)
    ) else base.copy(
        pink = accent.fill,
        onPink = base.ink,
        lavender = accent.soft,
        blue = accent.sparkle
    )
}

internal fun popColorScheme(p: PopColors): ColorScheme {
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = if (p.dark) p.pink else p.ink, onPrimary = if (p.dark) p.onPink else p.onInk,
        primaryContainer = p.pink, onPrimaryContainer = p.onPink,
        secondary = p.blue, onSecondary = Color.White,
        secondaryContainer = p.lavender, onSecondaryContainer = p.ink,
        tertiary = p.blue, tertiaryContainer = p.lavender, onTertiaryContainer = p.ink,
        background = p.bgTop, onBackground = p.ink,
        surface = p.card, onSurface = p.ink, onSurfaceVariant = p.muted,
        surfaceVariant = p.lavender, surfaceTint = Color.Transparent,
        surfaceContainerLowest = p.card, surfaceContainerLow = p.card, surfaceContainer = p.card,
        surfaceContainerHigh = p.card, surfaceContainerHighest = p.lavender,
        inverseSurface = p.ink, inverseOnSurface = p.onInk, inversePrimary = p.pink,
        outline = p.fieldLine, outlineVariant = p.fieldLine.copy(alpha = 0.4f),
        error = Color(0xFFE5484D)
    )
}

internal val PopTypography: Typography = Typography().let { t ->
    fun body(s: TextStyle) = s.copy(fontFamily = SpaceGrotesk)
    fun display(s: TextStyle, w: FontWeight = FontWeight.ExtraBold) = s.copy(fontFamily = Unbounded, fontWeight = w)
    Typography(
        displayLarge = display(t.displayLarge), displayMedium = display(t.displayMedium),
        displaySmall = display(t.displaySmall), headlineLarge = display(t.headlineLarge),
        headlineMedium = display(t.headlineMedium), headlineSmall = display(t.headlineSmall),
        titleLarge = display(t.titleLarge.copy(fontSize = 20.sp)),
        titleMedium = display(t.titleMedium.copy(fontSize = 15.sp, letterSpacing = 0.2.sp), FontWeight.SemiBold),
        titleSmall = display(t.titleSmall.copy(fontSize = 13.sp), FontWeight.SemiBold),
        bodyLarge = body(t.bodyLarge.copy(fontWeight = FontWeight.Medium)),
        bodyMedium = body(t.bodyMedium.copy(fontWeight = FontWeight.Medium)),
        bodySmall = body(t.bodySmall.copy(fontWeight = FontWeight.Medium)),
        labelLarge = body(t.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp)),
        labelMedium = body(t.labelMedium.copy(fontWeight = FontWeight.Bold)),
        labelSmall = body(t.labelSmall.copy(fontWeight = FontWeight.Bold))
    )
}

internal val PopShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp)
)

@Composable
internal fun ProvidePop(colors: PopColors, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalPop provides colors, content = content)

// ---------------------------------------------------------------- building blocks

internal val PopBorder = 2.dp
internal val PopShadow = 4.dp

/** Springy press: things sink into their shadow and bounce back. */
private val pressSpring = spring<Dp>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh)

/** Thick outline + hard offset shadow, the core of the look. */
internal fun Modifier.popSurface(
    fill: Color,
    border: Color,
    shadow: Color,
    shape: Shape = RoundedCornerShape(18.dp),
    offset: Dp = PopShadow
): Modifier = this
    .drawBehind {
        if (offset > 0.dp) {
            val o = offset.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            translate(o, o) { drawOutline(outline, shadow) }
        }
    }
    .clip(shape)
    .background(fill)
    .border(PopBorder, border, shape)

@Composable
internal fun PopCard(
    modifier: Modifier = Modifier,
    fill: Color = LocalPop.current.card,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalPop.current
    Column(
        // room on the right/bottom so the offset shadow is never clipped
        modifier.padding(end = PopShadow, bottom = PopShadow)
            .popSurface(fill, p.line, p.shadow)
            .padding(contentPadding),
        content = content
    )
}

/** Pill button. Primary = accent, secondary = card colour. Springs into its shadow when tapped. */
@Composable
internal fun PopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    height: Dp = 56.dp,
    content: @Composable RowScope.() -> Unit
) {
    val p = LocalPop.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            !enabled -> if (p.dark) Color(0xFF262C40) else Color(0xFFE6E8EE)
            primary -> p.pink
            else -> p.card
        }, label = "buttonFill"
    )
    val fg = when {
        !enabled -> p.muted
        primary -> p.onPink
        else -> p.ink
    }
    val shift by animateDpAsState(if (pressed && enabled) PopShadow else 0.dp, pressSpring, label = "press")
    Box(modifier.padding(end = PopShadow, bottom = PopShadow)) {
        Row(
            Modifier.offset(shift, shift)
                .defaultMinSize(minHeight = height)
                .fillMaxWidth()
                .popSurface(fill, if (enabled) p.line else p.fieldLine, p.shadow, RoundedCornerShape(50),
                    if (enabled) (PopShadow - shift).coerceAtLeast(0.dp) else 0.dp)
                .selectable(selected = false, enabled = enabled, role = Role.Button,
                    interactionSource = source, indication = null, onClick = onClick)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides fg) {
                ProvideTextStyle(PopTypography.titleMedium.copy(color = fg, fontWeight = FontWeight.ExtraBold)) {
                    content()
                }
            }
        }
    }
}

/** Small square icon button with outline + shadow (top bar). */
@Composable
internal fun PopIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val p = LocalPop.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shift by animateDpAsState(if (pressed) 3.dp else 0.dp, pressSpring, label = "iconPress")
    Box(Modifier.padding(end = 3.dp, bottom = 3.dp)) {
        Box(
            Modifier.offset(shift, shift).size(44.dp)
                .popSurface(p.card, p.line, p.shadow, RoundedCornerShape(14.dp), (3.dp - shift).coerceAtLeast(0.dp))
                .selectable(selected = false, role = Role.Button, interactionSource = source,
                    indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = description, tint = p.ink) }
    }
}

/** Small outlined label chip, like the "57%" / "Done" tags in the reference. */
@Composable
internal fun PopChip(text: String, modifier: Modifier = Modifier, fill: Color = LocalPop.current.lavender) {
    val p = LocalPop.current
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(fill)
            .border(1.5.dp, p.line, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
    ) { Text(text, style = PopTypography.labelMedium, color = if (fill == p.pink) p.onPink else p.ink) }
}

/** Big headline: first word in ink, second word in the accent with an ink outline and hard shadow. */
@Composable
internal fun PopHeadline(first: String, second: String, modifier: Modifier = Modifier, size: Int = 30) {
    val p = LocalPop.current
    val style = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.ExtraBold, fontSize = size.sp,
        lineHeight = (size * 1.05).sp, letterSpacing = (-0.5).sp)
    Column(modifier) {
        Text(first.uppercase(), style = style, color = p.ink, maxLines = 1)
        OutlinedWord(second.uppercase(), style, p.pink, p.line, p.headShadow)
    }
}

@Composable
internal fun OutlinedWord(text: String, style: TextStyle, fill: Color, outline: Color, shadow: Color) {
    Box {
        Text(text, style = style.copy(drawStyle = Stroke(width = 9f)), color = shadow, maxLines = 1,
            modifier = Modifier.offset(2.dp, 3.dp))
        Text(text, style = style.copy(drawStyle = Stroke(width = 9f)), color = outline, maxLines = 1)
        Text(text, style = style, color = fill, maxLines = 1)
    }
}

/**
 * Outlined progress bar with moving diagonal stripes while a download runs.
 * fraction == null: the striped fill sweeps across while waiting for a percentage.
 */
@Composable
internal fun PopProgress(fraction: Float?, modifier: Modifier = Modifier) {
    val p = LocalPop.current
    val shown by animateFloatAsState(
        (fraction ?: 0f).coerceIn(0f, 1f), tween(500, easing = FastOutSlowInEasing), label = "progress"
    )
    val motion = rememberInfiniteTransition(label = "stripes")
    val stripe by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "stripe")
    val sweep by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)), label = "sweep")
    Box(
        modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(50)).background(p.card)
            .border(PopBorder, if (p.dark) p.fieldLine else p.line, RoundedCornerShape(50)).padding(3.dp)
    ) {
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(50))) {
            val h = size.height
            val (start, width) = if (fraction != null) 0f to size.width * shown.coerceAtLeast(0.03f)
                else (sweep * 1.4f - 0.4f) * size.width to size.width * 0.4f
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(p.pink, Offset(start, 0f), Size(width, h), r)
            clipRect(start, 0f, start + width, h) {
                val gap = h * 1.6f
                var x = -gap * 2 + stripe * gap
                while (x < size.width + gap) {
                    val path = Path().apply {
                        moveTo(x, h); lineTo(x + h, 0f); lineTo(x + h + gap / 2.5f, 0f); lineTo(x + gap / 2.5f, h); close()
                    }
                    drawPath(path, p.onPink.copy(alpha = 0.22f))
                    x += gap
                }
            }
            drawRoundRect(p.line, Offset(start, 0f), Size(width, h), r, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** Sparkle that spins while something is busy (downloading). */
@Composable
internal fun SpinningSparkle(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val p = LocalPop.current
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "angle")
    val pulse by t.animateFloat(0.8f, 1.1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier.size(size).rotate(angle).scale(pulse)) { sparkle(center, this.size.minDimension / 2, p.blue) }
}

/** Dropdown arrow that turns as the menu opens and closes. */
@Composable
internal fun PopDropdownArrow(expanded: Boolean) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "arrow")
    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.rotate(turn))
}

@Composable
internal fun popFieldColors() = LocalPop.current.let { p ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = if (p.dark) p.pink else p.line, unfocusedBorderColor = p.fieldLine,
        disabledBorderColor = p.muted,
        focusedContainerColor = p.card, unfocusedContainerColor = p.card,
        focusedLabelColor = p.ink, unfocusedLabelColor = p.muted, cursorColor = p.blue,
        focusedTextColor = p.ink, unfocusedTextColor = p.ink,
        focusedTrailingIconColor = p.ink, unfocusedTrailingIconColor = p.ink
    )
}

@Composable
internal fun popSwitchColors() = LocalPop.current.let { p ->
    SwitchDefaults.colors(
        checkedThumbColor = p.onPink, checkedTrackColor = p.pink, checkedBorderColor = if (p.dark) p.pink else p.line,
        checkedIconColor = p.pink,
        uncheckedThumbColor = if (p.dark) p.muted else p.ink, uncheckedTrackColor = p.card,
        uncheckedBorderColor = p.fieldLine,
        disabledCheckedTrackColor = p.lavender, disabledUncheckedTrackColor = p.card,
        disabledUncheckedBorderColor = p.muted, disabledUncheckedThumbColor = p.muted
    )
}

/**
 * Bottom navigation as a floating outlined card. The accent pill slides to the selected tab.
 */
@Composable
internal fun PopNavBar(selectedIndex: Int, itemCount: Int, content: @Composable RowScope.() -> Unit) {
    val p = LocalPop.current
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(end = PopShadow, bottom = PopShadow)
                .popSurface(p.card, p.line, p.shadow, RoundedCornerShape(24.dp))
                .padding(horizontal = 6.dp, vertical = 8.dp)
        ) {
            val slot = maxWidth / itemCount
            val pillWidth = slot * 0.78f
            val x by animateDpAsState(
                slot * selectedIndex + (slot - pillWidth) / 2,
                spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow), label = "navPill"
            )
            Box(
                Modifier.offset(x = x, y = 2.dp).width(pillWidth).height(32.dp)
                    .clip(RoundedCornerShape(50)).background(p.pink).border(PopBorder, p.line, RoundedCornerShape(50))
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, content = content)
        }
    }
}

@Composable
internal fun RowScope.PopNavItem(selected: Boolean, onClick: () -> Unit, icon: ImageVector, label: String) {
    val p = LocalPop.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pop by animateFloatAsState(
        when { pressed -> 0.86f; selected -> 1.12f; else -> 1f },
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "navIcon"
    )
    val labelColor by animateColorAsState(if (selected) p.ink else p.muted, label = "navLabel")
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.Tab, interactionSource = source, indication = null, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.height(36.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (selected) p.onPink else p.ink,
                modifier = Modifier.size(20.dp).scale(pop))
        }
        Text(label, style = PopTypography.labelMedium, color = labelColor, maxLines = 1)
    }
}

/** Soft gradient with clusters of dots and four-point sparkles, like the reference. */
@Composable
internal fun PopBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val p = LocalPop.current
    val dots by animateColorAsState(p.blue, tween(400), label = "dots")
    Box(modifier.background(Brush.verticalGradient(listOf(p.bgTop, p.bgBottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            val dot = dots.copy(alpha = if (p.dark) 0.32f else 0.85f)
            dotCluster(Offset(size.width * 0.93f, size.height * 0.10f), dot, 7, 1f)
            dotCluster(Offset(size.width * 0.04f, size.height * 0.62f), dot, 6, -1f)
            dotCluster(Offset(size.width * 0.96f, size.height * 0.84f), dot, 5, 1f)
            sparkle(Offset(size.width * 0.80f, size.height * 0.055f), 11.dp.toPx(), dots)
            sparkle(Offset(size.width * 0.07f, size.height * 0.42f), 8.dp.toPx(), dots)
            sparkle(Offset(size.width * 0.91f, size.height * 0.52f), 13.dp.toPx(), dots)
            sparkle(Offset(size.width * 0.12f, size.height * 0.90f), 9.dp.toPx(), dots)
        }
        content()
    }
}

private fun DrawScope.dotCluster(center: Offset, color: Color, rows: Int, dir: Float) {
    val step = 11.dp.toPx()
    for (r in 0 until rows) for (c in 0 until rows - r) {
        val radius = (3.2f - (r + c) * 0.35f).coerceAtLeast(1.1f).dp.toPx()
        drawCircle(color, radius, Offset(center.x - dir * c * step, center.y + r * step - c * step * 0.15f))
    }
}

internal fun DrawScope.sparkle(center: Offset, r: Float, color: Color) {
    val w = r * 0.22f
    val path = Path().apply {
        moveTo(center.x, center.y - r)
        quadraticTo(center.x + w * 0.4f, center.y - w * 0.4f, center.x + r, center.y)
        quadraticTo(center.x + w * 0.4f, center.y + w * 0.4f, center.x, center.y + r)
        quadraticTo(center.x - w * 0.4f, center.y + w * 0.4f, center.x - r, center.y)
        quadraticTo(center.x - w * 0.4f, center.y - w * 0.4f, center.x, center.y - r)
        close()
    }
    drawPath(path, color)
}

/** Small sparkle used next to section titles. */
@Composable
internal fun SparkleTitle(text: String, modifier: Modifier = Modifier) {
    val p = LocalPop.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(14.dp)) { sparkle(center, size.minDimension / 2, p.blue) }
        Text(text.uppercase(), style = PopTypography.titleMedium, color = p.ink)
    }
}

@Composable
internal fun PopIconBadge(icon: ImageVector, modifier: Modifier = Modifier, fill: Color = LocalPop.current.pink) {
    val p = LocalPop.current
    Box(
        modifier.size(40.dp).clip(CircleShape).background(fill).border(PopBorder, p.line, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (fill == p.pink) p.onPink else p.ink,
            modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------- accent picker

/** Six preset swatches + a "custom" rainbow swatch. Selected swatch is lifted with a check. */
@Composable
internal fun AccentSwatches(selected: String, customHue: Float, onSelect: (String) -> Unit) {
    val p = LocalPop.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (ACCENTS + customAccent(customHue)).forEach { accent ->
            val isSelected = accent.name == selected
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val lift by animateDpAsState(if (isSelected && !pressed) 3.dp else 0.dp, pressSpring, label = "swatch")
            val grow by animateFloatAsState(if (pressed) 0.88f else 1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "swatchScale")
            Box(Modifier.padding(end = 3.dp, bottom = 3.dp).scale(grow)) {
                Box(
                    Modifier.offset(-lift, -lift).size(38.dp)
                        .popSurface(
                            accent.fill, p.line, if (p.dark) p.ink else p.shadow, CircleShape, lift
                        )
                        .then(
                            if (accent.name == ACCENT_CUSTOM) Modifier.background(
                                Brush.sweepGradient(spectrumColors)
                            ) else Modifier
                        )
                        .border(PopBorder, p.line, CircleShape)
                        .selectable(isSelected, interactionSource = source, indication = null, role = Role.RadioButton) {
                            onSelect(accent.name)
                        }
                        .semantics { contentDescription = "${accent.name} accent" },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            Modifier.size(20.dp).clip(CircleShape).background(p.card).border(1.5.dp, p.line, CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.Check, contentDescription = null, tint = p.ink, modifier = Modifier.size(14.dp)) }
                    }
                }
            }
        }
    }
}

private val spectrumColors = (0..12).map { Color.hsv((it * 30f).mod(360f), 0.55f, 1f) }

/** Rainbow bar: drag or tap anywhere to pick the accent hue. */
@Composable
internal fun AccentSpectrum(hue: Float, onHueChange: (Float) -> Unit, onHueChosen: (Float) -> Unit) {
    val p = LocalPop.current
    val change by rememberUpdatedState(onHueChange)
    val chosen by rememberUpdatedState(onHueChosen)
    var widthPx by remember { mutableFloatStateOf(1f) }
    var current by remember { mutableFloatStateOf(hue) }
    current = hue
    fun hueAt(x: Float) = (x / widthPx).coerceIn(0f, 1f) * 360f
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "Accent colour spectrum" }
    ) {
        widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Box(
            Modifier.fillMaxWidth().height(26.dp).align(Alignment.Center)
                .clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(spectrumColors))
                .border(PopBorder, p.line, RoundedCornerShape(50))
                .pointerInput(Unit) {
                    detectTapGestures { change(hueAt(it.x)); chosen(hueAt(it.x)) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { change(hueAt(it.x)) },
                        onDragEnd = { chosen(current) },
                        onDragCancel = { chosen(current) }
                    ) { pointer, _ -> change(hueAt(pointer.position.x)) }
                }
        )
        val knob = 34.dp
        val x = (maxWidth - knob) * (hue.mod(360f) / 360f)
        Box(
            Modifier.offset(x = x).size(knob).align(Alignment.CenterStart)
                .popSurface(customAccent(hue).fill, p.line, if (p.dark) p.ink else p.shadow, CircleShape, 2.dp)
        )
    }
}
