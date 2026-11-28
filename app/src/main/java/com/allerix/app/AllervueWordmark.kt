package com.allerix.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand wordmark: open gradient orbit, cyan satellite, and two-tone lettering. */
@Composable
fun AllervueWordmark(
    modifier: Modifier = Modifier,
    emblemSize: Dp = 72.dp,
    textSize: androidx.compose.ui.unit.TextUnit = 42.sp
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(emblemSize)) {
            val stroke = size.minDimension * .105f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color(0xFFFF665D), Color(0xFFFF3F88), Color(0xFF9746E8), Color(0xFFFF665D))
                ),
                startAngle = -78f,
                sweepAngle = 315f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawCircle(
                brush = Brush.linearGradient(listOf(Color(0xFFFF5D70), Color(0xFFB83FE0))),
                radius = size.minDimension * .155f,
                center = Offset(size.width * .49f, size.height * .52f)
            )
            drawCircle(
                color = Color(0xFF28D5D0),
                radius = size.minDimension * .075f,
                center = Offset(size.width * .82f, size.height * .12f)
            )
        }
        Spacer(Modifier.width(emblemSize * .16f))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Color(0xFFF8F5FF))) { append("a") }
                withStyle(SpanStyle(color = Color(0xFFFF665D))) { append("ll") }
                withStyle(SpanStyle(color = Color(0xFFF8F5FF))) { append("ervue") }
            },
            fontSize = textSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1.6).sp,
            maxLines = 1
        )
    }
}
