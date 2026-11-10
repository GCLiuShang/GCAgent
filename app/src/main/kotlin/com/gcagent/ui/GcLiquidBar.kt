package com.gcagent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.gcagent.ui.animation.DampedDragAnimation
import com.gcagent.ui.animation.InteractiveHighlight
import com.gcagent.ui.liquid.InnerShadow
import com.gcagent.ui.liquid.innerShadow
import com.gcagent.ui.liquid.lens
import com.gcagent.ui.liquid.rememberCombinedBackdrop
import com.gcagent.ui.liquid.vibrancy
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

private val LiquidSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f
private const val GRAVITY_ANGLE_STEP_RAD = (3.0 * PI / 180.0).toFloat()

@Composable
private fun rememberQuantizedGravityAngle(): State<Float> {
    val tiltState = rememberDeviceTilt()
    return remember(tiltState) {
        derivedStateOf {
            val tilt = tiltState.value
            val magnitudeSquared = tilt.gravityX * tilt.gravityX + tilt.gravityY * tilt.gravityY
            if (magnitudeSquared > GRAVITY_DIR_THRESHOLD_SQ) {
                (atan2(tilt.gravityY, tilt.gravityX) / GRAVITY_ANGLE_STEP_RAD).roundToInt() * GRAVITY_ANGLE_STEP_RAD
            } else {
                (-PI / 2).toFloat()
            }
        }
    }
}

@Composable
private fun rememberGravityRotatedHighlight(
    base: Highlight,
    extraDegrees: Float = 0f,
): State<Highlight> {
    val baseStyle = base.style as BloomStroke
    val angle = rememberQuantizedGravityAngle()
    return remember(angle, base, extraDegrees) {
        derivedStateOf {
            val basePrimary = baseStyle.primaryLight
            val rad = angle.value + (extraDegrees * PI / 180.0).toFloat()
            base.copy(
                style = baseStyle.copy(
                    primaryLight = basePrimary.copy(
                        position = LightPosition(
                            x = LIGHT_REF_X + cos(rad),
                            y = LIGHT_REF_Y + sin(rad),
                            z = basePrimary.position.z,
                        ),
                    ),
                ),
            )
        }
    }
}

/**
 * 严格按 KernelSU FloatingBottomBar 的液态玻璃路径实现的文本底栏。
 * 主 pill + tabs 捕获层 + 指示器 + lens/色散/内阴影/重力高光/弹簧拖动。
 */
@Composable
fun GcLiquidBar(
    labels: List<String>,
    selected: Int,
    opacity: Int,
    backdrop: Backdrop?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabsCount = labels.size.coerceAtLeast(1)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val isLtr = layoutDirection == LayoutDirection.Ltr

    var totalWidthPx by remember { mutableFloatStateOf(0f) }
    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var currentIndex by remember { mutableIntStateOf(selected) }
    val selectedNow by rememberUpdatedState(selected)
    val onSelectNow by rememberUpdatedState(onSelect)

    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val fraction = (offsetAnimation.value / totalWidthPx.coerceAtLeast(1f)).coerceIn(-1f, 1f)
    val panelOffset = rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))

    fun indexAt(x: Float): Int {
        val logicalX = if (isLtr) x else totalWidthPx - x
        val width = tabWidthPx.coerceAtLeast(1f)
        return ((logicalX - rubberBandPx) / width).toInt().coerceIn(0, tabsCount - 1)
    }

    val drag = remember(scope, tabsCount) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = selected.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset -> offset.x in 0f..totalWidthPx.coerceAtLeast(1f) },
            onDragStarted = { position -> updateValue(indexAt(position.x).toFloat()) },
            onDragStopped = {
                val target = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                if (currentIndex != target) {
                    currentIndex = target
                    onSelectNow(target)
                }
                updateValue(target.toFloat())
                scope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDragCancelled = {
                updateValue(currentIndex.toFloat())
                scope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0f && dragAmount.x != 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .coerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                    scope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x) }
                }
            },
        )
    }

    LaunchedEffect(selectedNow) {
        if (currentIndex != selectedNow) {
            currentIndex = selectedNow
            drag.animateToValue(selectedNow.toFloat())
        }
    }

    val interactiveHighlight = remember(scope, tabWidthPx, drag) {
        InteractiveHighlight(
            animationScope = scope,
            position = { size, _ ->
                Offset(
                    if (isLtr) (drag.value + 0.5f) * tabWidthPx + panelOffset
                    else size.width - (drag.value + 0.5f) * tabWidthPx + panelOffset,
                    size.height / 2f,
                )
            },
        )
    }

    val baseHighlight = rememberGravityRotatedHighlight(LiquidSpecular, extraDegrees = -45f)
    val pillHighlight = rememberGravityRotatedHighlight(LiquidSpecular, extraDegrees = 90f)
    val tabsBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = if (backdrop != null) rememberCombinedBackdrop(backdrop, tabsBackdrop) else null
    val shape = CircleShape
    val transparency = opacity.coerceIn(0, 100) / 100f
    val tintAlpha = 0.4f * (1f - transparency)
    val containerColor = LiquidSurface.copy(alpha = tintAlpha)

    fun activateTab(i: Int) {
        if (i !in 0 until tabsCount) return
        currentIndex = i
        onSelectNow(i)
        drag.animateToValue(i.toFloat())
    }

    Box(modifier.fillMaxWidth().height(64.dp).then(drag.modifier), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    val content = totalWidthPx - with(density) { 8.dp.toPx() }
                    tabWidthPx = (content / tabsCount).coerceAtLeast(0f)
                }
                .selectableGroup()
                .dropShadow(
                    shape = shape,
                    shadow = Shadow(
                        radius = 10.dp,
                        color = Color.Black,
                        alpha = 0.2f,
                    ),
                )
                .graphicsLayer { translationX = panelOffset }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                padding = maxOf(padding, 40.dp.toPx())
                                vibrancy()
                                blur(4.dp.toPx(), 4.dp.toPx())
                                lens(24.dp.toPx(), 24.dp.toPx())
                            },
                            highlight = { baseHighlight.value.copy(alpha = 0.75f) },
                            layerBlock = {
                                val w = size.width.coerceAtLeast(1f)
                                val s = lerp(1f, 1f + 16.dp.toPx() / w, drag.pressProgress)
                                scaleX = s
                                scaleY = s
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                    } else {
                        Modifier.background(containerColor, shape)
                    },
                )
                .innerShadow(shape) {
                    InnerShadow(
                        radius = 10.dp,
                        color = Color.Black.copy(alpha = 0.10f),
                        alpha = 0.7f,
                    )
                }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, label ->
                TabLabel(
                    label = label,
                    selected = backdrop == null && i == selected,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics(mergeDescendants = true) {
                            this.selected = i == selected
                            role = Role.Tab
                            onClick {
                                activateTab(i)
                                true
                            }
                        },
                )
            }
        }

        if (backdrop != null) {
            Row(
                Modifier
                    .fillMaxSize()
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            padding = maxOf(padding, 40.dp.toPx())
                            vibrancy()
                            blur(4.dp.toPx(), 4.dp.toPx())
                            lens(24.dp.toPx(), 24.dp.toPx())
                        },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { i, label ->
                    TabLabel(
                        label = label,
                        selected = i == selected,
                        tintAll = true,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clearAndSetSemantics {},
                    )
                }
            }
        }

        if (tabWidthPx > 0f) {
            val widthDp = with(density) { tabWidthPx.toDp() }
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .graphicsLayer {
                        translationX = if (isLtr) drag.value * tabWidthPx + panelOffset
                        else -drag.value * tabWidthPx + panelOffset
                    }
                    .width(widthDp)
                    .height(56.dp)
                    .then(
                        if (backdrop != null && combinedBackdrop != null) {
                            Modifier
                                .drawBackdrop(
                                    backdrop = combinedBackdrop,
                                    shape = { shape },
                                    effects = {
                                        lens(
                                            refractionHeight = 10.dp.toPx() * drag.pressProgress,
                                            refractionAmount = 14.dp.toPx() * drag.pressProgress,
                                            depthEffect = true,
                                            chromaticAberration = 0.5f,
                                        )
                                    },
                                    highlight = { pillHighlight.value.copy(alpha = drag.pressProgress) },
                                    layerBlock = {
                                        scaleX = drag.scaleX
                                        scaleY = drag.scaleY
                                        val v = drag.velocity / 10f
                                        scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)
                                        scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
                                    },
                                    onDrawSurface = {
                                        drawRect(Color.Black.copy(alpha = 0.1f * (1f - drag.pressProgress)))
                                        drawRect(Color.Black.copy(alpha = 0.03f * drag.pressProgress))
                                    },
                                )
                                .innerShadow(shape) {
                                    InnerShadow(
                                        radius = 8.dp * drag.pressProgress,
                                        color = Color.Black.copy(alpha = 0.15f),
                                        alpha = drag.pressProgress,
                                    )
                                }
                        } else {
                            Modifier
                                .clip(shape)
                                .background(LiquidBlue.copy(alpha = 0.15f), shape)
                        },
                    ),
            )
        }

        Box(
            Modifier
                .matchParentSize()
                .then(interactiveHighlight.modifier),
        )
    }
}

@Composable
private fun TabLabel(
    label: String,
    selected: Boolean,
    tintAll: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            text = label,
            color = if (tintAll || selected) LiquidBlue else Color.Black,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val LiquidSurface = Color(0xFFF2F5FA)
private val LiquidBlue = Color(0xFF4397F7)
