package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.MoneyFormatter
import com.example.domain.WorkDistributionEngine.AgencyAnalyticsSeries
import com.example.domain.WorkDistributionEngine.MonthlyAnalyticsPoint
import com.example.ui.theme.*
import kotlin.math.roundToInt

/**
 * Native Jetpack Compose Canvas interactive visualizer for Cumulative Project Progress (%),
 * Monthly Collected Revenue vs. 3-Month Revenue Forecast.
 */
@Composable
fun AnalyticsVisualizer(
    series: AgencyAnalyticsSeries,
    modifier: Modifier = Modifier,
    title: String = "Progress & Revenue Forecast"
) {
    val palette = XMaxTheme.colors
    val allPoints = remember(series) { series.historyPoints + series.forecastPoints }
    var selectedIdx by remember(allPoints.size) {
        mutableIntStateOf((series.historyPoints.lastIndex).coerceAtLeast(0))
    }
    val selectedPoint: MonthlyAnalyticsPoint? = allPoints.getOrNull(selectedIdx)
    val textMeasurer = rememberTextMeasurer()

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.outline),
        shadowElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("analytics_visualizer_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = palette.softVioletBg,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.TrendingUp,
                                contentDescription = title,
                                tint = VioletPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = title,
                            style = XMaxType.headline,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Tap any month bar to inspect metrics & forecast (*)",
                            style = XMaxType.caption1,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // KPI Summary Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnalyticsKpiPill(
                    label = "Avg Progress",
                    value = "${series.averageCompletionPercent}%",
                    tint = EmeraldApproved,
                    bg = palette.mintBg,
                    modifier = Modifier.weight(1f)
                )
                AnalyticsKpiPill(
                    label = "Collection",
                    value = "${series.collectionRatePercent}%",
                    tint = palette.skyInk,
                    bg = palette.skyBg,
                    modifier = Modifier.weight(1f)
                )
                AnalyticsKpiPill(
                    label = "3M Forecast",
                    value = compactInr(series.projectedNext3MonthsPaise),
                    tint = VioletPrimary,
                    bg = palette.softVioletBg,
                    modifier = Modifier.weight(1f)
                )
            }

            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendSwatch(color = VioletPrimary, label = "Collected")
                LegendSwatch(color = VioletPrimary.copy(alpha = 0.35f), label = "Forecast*")
                LegendSwatch(color = EmeraldApproved, label = "Completion %", isLine = true)
            }

            // Interactive Canvas
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surfaceMuted.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                val maxPaise = remember(allPoints) {
                    val m = allPoints.maxOfOrNull { maxOf(it.collectedPaise, it.forecastPaise) } ?: 0L
                    m.coerceAtLeast(10_00_00L) // minimum ₹10k scale
                }
                val barCollectedColor = VioletPrimary
                val barForecastColor = VioletPrimary.copy(alpha = 0.32f)
                val barSelectedBorder = palette.textPrimary
                val lineColor = EmeraldApproved
                val gridColor = palette.outline
                val labelColor = palette.textSecondary

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(allPoints.size) {
                            detectTapGestures { tap ->
                                if (allPoints.isNotEmpty() && size.width > 0) {
                                    val slotW = size.width.toFloat() / allPoints.size.toFloat()
                                    val idx = (tap.x / slotW).toInt().coerceIn(0, allPoints.lastIndex)
                                    selectedIdx = idx
                                }
                            }
                        }
                ) {
                    val topPad = 14.dp.toPx()
                    val bottomPad = 24.dp.toPx()
                    val chartHeight = (size.height - topPad - bottomPad).coerceAtLeast(24f)
                    val slotW = if (allPoints.isEmpty()) size.width else size.width / allPoints.size.toFloat()
                    val barW = (slotW * 0.46f).coerceIn(10f, 36.dp.toPx())

                    // Horizontal guide lines (0%, 50%, 100%)
                    listOf(0f, 0.5f, 1f).forEach { frac ->
                        val y = topPad + chartHeight * (1f - frac)
                        drawLine(
                            color = gridColor.copy(alpha = 0.55f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        )
                    }

                    val lineOffsets = mutableListOf<Offset>()

                    allPoints.forEachIndexed { idx, pt ->
                        val centerX = slotW * idx + slotW / 2f
                        val valuePaise = if (pt.isForecast) pt.forecastPaise else pt.collectedPaise
                        val valueFrac = (valuePaise.toDouble() / maxPaise.toDouble()).toFloat().coerceIn(0f, 1f)
                        val barH = (chartHeight * valueFrac).coerceAtLeast(if (valuePaise > 0L) 4.dp.toPx() else 2.dp.toPx())
                        val barTop = topPad + chartHeight - barH
                        val barLeft = centerX - barW / 2f
                        val isSelected = idx == selectedIdx

                        drawRoundRect(
                            color = if (pt.isForecast) barForecastColor else barCollectedColor.copy(alpha = if (isSelected) 1f else 0.8f),
                            topLeft = Offset(barLeft, barTop),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                        )

                        if (isSelected) {
                            drawRoundRect(
                                color = barSelectedBorder,
                                topLeft = Offset(barLeft - 1.5.dp.toPx(), barTop - 1.5.dp.toPx()),
                                size = Size(barW + 3.dp.toPx(), barH + 3.dp.toPx()),
                                cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx()),
                                style = Stroke(width = 1.6.dp.toPx())
                            )
                        }

                        // Completion % point
                        val pctFrac = (pt.cumulativeCompletionPct / 100f).coerceIn(0f, 1f)
                        val lineY = topPad + chartHeight * (1f - pctFrac)
                        lineOffsets += Offset(centerX, lineY)

                        // X-Axis Label
                        val measured = textMeasurer.measure(
                            text = pt.label,
                            style = TextStyle(
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) barCollectedColor else labelColor
                            )
                        )
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(
                                (centerX - measured.size.width / 2f).coerceIn(0f, (size.width - measured.size.width).coerceAtLeast(0f)),
                                topPad + chartHeight + 6.dp.toPx()
                            )
                        )
                    }

                    // Cumulative progress line + forecast dashed extension
                    if (lineOffsets.size >= 2) {
                        val histCount = series.historyPoints.size
                        val histPath = Path()
                        for (i in 0 until histCount.coerceAtMost(lineOffsets.size)) {
                            val o = lineOffsets[i]
                            if (i == 0) histPath.moveTo(o.x, o.y) else histPath.lineTo(o.x, o.y)
                        }
                        drawPath(
                            path = histPath,
                            color = lineColor,
                            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                        )

                        if (histCount in 1 until lineOffsets.size) {
                            val forecastPath = Path()
                            forecastPath.moveTo(lineOffsets[histCount - 1].x, lineOffsets[histCount - 1].y)
                            for (i in histCount until lineOffsets.size) {
                                forecastPath.lineTo(lineOffsets[i].x, lineOffsets[i].y)
                            }
                            drawPath(
                                path = forecastPath,
                                color = lineColor.copy(alpha = 0.7f),
                                style = Stroke(
                                    width = 2.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                                )
                            )
                        }

                        lineOffsets.forEachIndexed { idx, o ->
                            val isSel = idx == selectedIdx
                            drawCircle(
                                color = Color.White,
                                radius = if (isSel) 5.dp.toPx() else 3.5.dp.toPx(),
                                center = o
                            )
                            drawCircle(
                                color = lineColor,
                                radius = if (isSel) 3.6.dp.toPx() else 2.5.dp.toPx(),
                                center = o
                            )
                        }
                    }
                }
            }

            // Selected Month Inspector Strip
            if (selectedPoint != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.softVioletBg.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, VioletPrimary.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (selectedPoint.isForecast) "${selectedPoint.label.trimEnd('*')} (Projected)" else "${selectedPoint.label} Actual",
                                style = XMaxType.subheadlineSemibold,
                                color = palette.textPrimary
                            )
                            val hrs = selectedPoint.loggedMinutes / 60.0
                            Text(
                                text = "Progress: ${selectedPoint.cumulativeCompletionPct.roundToInt()}%" +
                                    if (!selectedPoint.isForecast && hrs > 0.0) " • ${"%.1f".format(hrs)}h logged" else "",
                                style = XMaxType.caption1,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = MoneyFormatter.formatInr(if (selectedPoint.isForecast) selectedPoint.forecastPaise else selectedPoint.collectedPaise),
                            style = XMaxType.headline.tabular(),
                            color = VioletPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsKpiPill(
    label: String,
    value: String,
    tint: Color,
    bg: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = XMaxType.caption2,
                color = tint.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = XMaxType.subheadlineSemibold.tabular(),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LegendSwatch(
    color: Color,
    label: String,
    isLine: Boolean = false
) {
    val palette = XMaxTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (isLine) {
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(color)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
        Text(
            text = label,
            style = XMaxType.caption1,
            color = palette.textSecondary,
            maxLines = 1
        )
    }
}

private fun compactInr(paise: Long): String {
    val rupees = paise / 100.0
    return when {
        rupees >= 1_00_000.0 -> "₹${"%.1f".format(rupees / 1_00_000.0)}L"
        rupees >= 1_000.0 -> "₹${"%.1f".format(rupees / 1_000.0)}K"
        else -> MoneyFormatter.formatInr(paise)
    }
}
