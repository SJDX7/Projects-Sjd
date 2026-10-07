package com.example.watchtogetherbysjd

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

val REACTION_EMOJIS = listOf("❤️", "😂", "😮", "😢", "👏", "🔥")

class FloatingReaction(
    val id: Long,
    val emoji: String,
    val xFraction: Float,
    val seed: Float
)

@Composable
fun ReactionOverlay(
    reactions: List<FloatingReaction>,
    onDone: (FloatingReaction) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        for (r in reactions) {
            key(r.id) {
                FloatingEmoji(r, w, h) { onDone(r) }
            }
        }
    }
}

@Composable
private fun FloatingEmoji(item: FloatingReaction, maxW: Int, maxH: Int, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(item.id) {
        progress.animateTo(1f, tween(durationMillis = 2800, easing = LinearEasing))
        onDone()
    }
    Text(
        text = item.emoji,
        fontSize = 36.sp,
        modifier = Modifier
            .offset {
                val p = progress.value
                val sway = sin(p * 6f + item.seed) * 30f
                val x = (item.xFraction * (maxW - 120)).toInt() + sway.toInt()
                val y = (maxH * (0.88f - 0.75f * p)).toInt()
                IntOffset(x, y)
            }
            .graphicsLayer {
                val p = progress.value
                alpha = if (p < 0.7f) 1f else ((1f - p) / 0.3f).coerceIn(0f, 1f)
            }
    )
}

@Composable
fun ReactionBar(
    onReact: (String) -> Unit,
    vertical: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (vertical) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            REACTION_EMOJIS.forEach { e ->
                IconBubble(onClick = { onReact(e) }) {
                    Text(e, fontSize = 18.sp)
                }
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            REACTION_EMOJIS.forEach { e ->
                Text(
                    text = e,
                    fontSize = 28.sp,
                    modifier = Modifier
                        .clickable { onReact(e) }
                        .padding(8.dp)
                )
            }
        }
    }
}