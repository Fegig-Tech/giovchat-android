package app.giovchat.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The few glyphs the chat needs, drawn on a canvas so the SDK carries no icon font or icon library.

internal enum class GiovGlyph { CLOSE, PLUS, ARROW_UP, FILE, BROKEN_IMAGE, SHIELD }

@Composable
internal fun GiovIcon(glyph: GiovGlyph, color: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            GiovGlyph.CLOSE -> {
                drawLine(color, Offset(s * 0.25f, s * 0.25f), Offset(s * 0.75f, s * 0.75f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(s * 0.75f, s * 0.25f), Offset(s * 0.25f, s * 0.75f), stroke.width, StrokeCap.Round)
            }
            GiovGlyph.PLUS -> {
                drawLine(color, Offset(s * 0.2f, s * 0.5f), Offset(s * 0.8f, s * 0.5f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(s * 0.5f, s * 0.2f), Offset(s * 0.5f, s * 0.8f), stroke.width, StrokeCap.Round)
            }
            GiovGlyph.ARROW_UP -> {
                drawLine(color, Offset(s * 0.5f, s * 0.82f), Offset(s * 0.5f, s * 0.2f), stroke.width, StrokeCap.Round)
                val head = Path().apply {
                    moveTo(s * 0.2f, s * 0.48f)
                    lineTo(s * 0.5f, s * 0.18f)
                    lineTo(s * 0.8f, s * 0.48f)
                }
                drawPath(head, color, style = stroke)
            }
            GiovGlyph.FILE -> {
                val page = Path().apply {
                    moveTo(s * 0.22f, s * 0.1f)
                    lineTo(s * 0.6f, s * 0.1f)
                    lineTo(s * 0.8f, s * 0.3f)
                    lineTo(s * 0.8f, s * 0.9f)
                    lineTo(s * 0.22f, s * 0.9f)
                    close()
                }
                drawPath(page, color, style = Stroke(width = s * 0.08f, join = StrokeJoin.Round))
                drawLine(color, Offset(s * 0.34f, s * 0.58f), Offset(s * 0.68f, s * 0.58f), s * 0.08f, StrokeCap.Round)
                drawLine(color, Offset(s * 0.34f, s * 0.74f), Offset(s * 0.6f, s * 0.74f), s * 0.08f, StrokeCap.Round)
            }
            GiovGlyph.SHIELD -> {
                val shield = Path().apply {
                    moveTo(s * 0.5f, s * 0.1f)
                    lineTo(s * 0.82f, s * 0.24f)
                    cubicTo(s * 0.82f, s * 0.58f, s * 0.68f, s * 0.8f, s * 0.5f, s * 0.9f)
                    cubicTo(s * 0.32f, s * 0.8f, s * 0.18f, s * 0.58f, s * 0.18f, s * 0.24f)
                    close()
                }
                drawPath(shield, color, style = Stroke(width = s * 0.08f, join = StrokeJoin.Round))
            }
            GiovGlyph.BROKEN_IMAGE -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(s * 0.12f, s * 0.18f),
                    size = Size(s * 0.76f, s * 0.64f),
                    cornerRadius = CornerRadius(s * 0.08f),
                    style = Stroke(width = s * 0.08f),
                )
                val hills = Path().apply {
                    moveTo(s * 0.18f, s * 0.74f)
                    lineTo(s * 0.4f, s * 0.46f)
                    lineTo(s * 0.55f, s * 0.64f)
                    lineTo(s * 0.64f, s * 0.54f)
                    lineTo(s * 0.82f, s * 0.74f)
                }
                drawPath(hills, color, style = Stroke(width = s * 0.07f, join = StrokeJoin.Round))
            }
        }
    }
}
