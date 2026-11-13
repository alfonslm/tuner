package io.github.alfonslm.tuner.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Cents shown from the centre to either end of the scale. */
const val METER_RANGE_CENTS = 50f

/** Space left at either end of the scale; the waterfall below uses the same so the two line up. */
val METER_INSET = 2.dp

/**
 * A linear cents scale with a moving needle. The green zone marks the in-tune
 * tolerance; readings beyond the scale rest against its end.
 *
 * @param cents offset to show, or `null` to park the needle in the middle
 * @param dimmed true for a held reading (the note has stopped)
 */
@Composable
fun Meter(cents: Float?, tone: Color, inTuneCents: Float, dimmed: Boolean, modifier: Modifier = Modifier) {
    val position by animateFloatAsState(
        targetValue = ((cents ?: 0f) / METER_RANGE_CENTS).coerceIn(-1f, 1f),
        animationSpec = tween(durationMillis = 80),
        label = "needle",
    )
    val needleAlpha by animateFloatAsState(if (dimmed) 0.35f else 1f, label = "needleAlpha")

    // The same information is spoken by the cents readout next to it.
    Column(modifier.clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (label in listOf("−50", "−25", "0", "+25", "+50")) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = TunerColors.Muted)
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            val inset = METER_INSET.toPx()
            val half = size.width / 2 - inset
            val centreX = size.width / 2
            val centreY = size.height / 2
            fun x(fraction: Float) = centreX + fraction * half

            // In-tune zone.
            val zoneHalf = inTuneCents / METER_RANGE_CENTS * half
            drawRoundRect(
                color = TunerColors.Good.copy(alpha = 0.22f),
                topLeft = Offset(centreX - zoneHalf, 6.dp.toPx()),
                size = Size(2 * zoneHalf, size.height - 12.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )

            // Scale: a tick every 5 cents, taller every 25.
            drawLine(TunerColors.Line, Offset(x(-1f), centreY), Offset(x(1f), centreY), strokeWidth = 2.dp.toPx())
            for (step in -10..10) {
                val tickHalf = (if (step % 5 == 0) 10.dp else 5.dp).toPx()
                val tickX = x(step / 10f)
                drawLine(TunerColors.Line, Offset(tickX, centreY - tickHalf), Offset(tickX, centreY + tickHalf), strokeWidth = 1.dp.toPx())
            }

            drawLine(
                color = tone.copy(alpha = needleAlpha),
                start = Offset(x(position), 0f),
                end = Offset(x(position), size.height),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
