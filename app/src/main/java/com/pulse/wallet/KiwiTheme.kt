package com.pulse.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

object KiwiPalette {
    val Leaf = Color(0xFFBED991)
    val Forest = Color(0xFF111E17)
    val Bark = Color(0xFFB58A60)
    val Honey = Color(0xFFF1C77A)
}

@Composable
fun KiwiWalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = KiwiPalette.Leaf,
            onPrimary = Color(0xFF21370C),
            primaryContainer = Color(0xFF344B26),
            onPrimaryContainer = Color(0xFFE0F2BD),
            secondary = Color(0xFFD9B98E),
            onSecondary = Color(0xFF3B2B16),
            secondaryContainer = Color(0xFF4C3B27),
            tertiary = KiwiPalette.Honey,
            background = KiwiPalette.Forest,
            onBackground = Color(0xFFF1EEDD),
            surface = Color(0xFF1A2920),
            onSurface = Color(0xFFF1EEDD),
            surfaceVariant = Color(0xFF304035),
            onSurfaceVariant = Color(0xFFB9C5B7),
            outline = Color(0xFF82917F),
            error = Color(0xFFFFB4A6)
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content
    )
}

/** Small original vector mascot: feathered body, long bill and sturdy kiwi feet. */
@Composable
fun KiwiBird(modifier: Modifier = Modifier) {
    Canvas(modifier.size(56.dp).semantics { contentDescription = "Kiwi bird" }) {
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        drawCircle(Color(0xFF2B402C), radius = size.minDimension * .49f, center = point(.5f, .5f))
        drawLine(KiwiPalette.Honey, point(.34f, .70f), point(.31f, .84f), size.width * .035f, StrokeCap.Round)
        drawLine(KiwiPalette.Honey, point(.49f, .70f), point(.50f, .84f), size.width * .035f, StrokeCap.Round)
        drawLine(KiwiPalette.Honey, point(.27f, .85f), point(.37f, .85f), size.width * .035f, StrokeCap.Round)
        drawLine(KiwiPalette.Honey, point(.46f, .85f), point(.56f, .85f), size.width * .035f, StrokeCap.Round)
        drawOval(KiwiPalette.Bark, topLeft = point(.15f, .33f), size = Size(size.width * .50f, size.height * .43f))
        drawCircle(KiwiPalette.Bark, radius = size.minDimension * .17f, center = point(.59f, .37f))
        val bill = Path().apply {
            moveTo(size.width * .69f, size.height * .37f)
            lineTo(size.width * .95f, size.height * .54f)
            lineTo(size.width * .68f, size.height * .44f)
            close()
        }
        drawPath(bill, KiwiPalette.Honey)
        drawCircle(KiwiPalette.Forest, size.minDimension * .028f, point(.62f, .335f))
        drawCircle(Color(0xFFFFF7DD), size.minDimension * .009f, point(.628f, .325f))
        for (i in 0..2) {
            val x = .26f + i * .09f
            drawLine(Color(0xFF8D6948), point(x, .48f), point(x - .035f, .59f), size.width * .018f, StrokeCap.Round)
        }
        drawLine(KiwiPalette.Leaf, point(.14f, .24f), point(.28f, .31f), size.width * .022f, StrokeCap.Round)
        drawOval(KiwiPalette.Leaf, topLeft = point(.12f, .16f), size = Size(size.width * .13f, size.height * .08f))
    }
}
