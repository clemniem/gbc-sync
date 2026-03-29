package com.gbcsync.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Game Boy 4-shade green palette
private val GB_DARKEST = Color(0xFF0F380F)
private val GB_DARK = Color(0xFF306230)
private val GB_LIGHT = Color(0xFF8BAC0F)
private val GB_LIGHTEST = Color(0xFF9BBC0F)

private val PALETTE = arrayOf(GB_DARKEST, GB_DARK, GB_LIGHT, GB_LIGHTEST)

private const val T = 3 // transparent

// GB Camera sprite (18w x 16h) — boxy camera with round lens
private val CAMERA_SPRITE =
    arrayOf(
        intArrayOf(T, T, T, T, T, T, T, 1, 1, 1, 1, T, T, T, T, T, T, T), // viewfinder
        intArrayOf(T, T, T, T, T, T, T, 1, 2, 2, 1, T, T, T, T, T, T, T),
        intArrayOf(T, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, T), // top edge
        intArrayOf(T, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, T),
        intArrayOf(T, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, T), // lens area
        intArrayOf(T, 0, 1, 1, 1, 0, 0, 2, 2, 2, 2, 0, 0, 1, 1, 1, 0, T),
        intArrayOf(T, 0, 1, 1, 1, 0, 2, 3, 3, 2, 2, 2, 0, 1, 1, 1, 0, T),
        intArrayOf(T, 0, 1, 1, 1, 0, 2, 3, 2, 2, 2, 2, 0, 1, 1, 1, 0, T),
        intArrayOf(T, 0, 1, 1, 1, 0, 2, 2, 2, 2, 2, 2, 0, 1, 1, 1, 0, T),
        intArrayOf(T, 0, 1, 1, 1, 0, 0, 2, 2, 2, 2, 0, 0, 1, 1, 1, 0, T),
        intArrayOf(T, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, T),
        intArrayOf(T, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, T),
        intArrayOf(T, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, T), // bottom edge
        intArrayOf(T, T, T, T, 0, 1, 0, T, T, T, T, 0, 1, 0, T, T, T, T), // legs/stand
        intArrayOf(T, T, T, T, 0, 1, 0, T, T, T, T, 0, 1, 0, T, T, T, T),
        intArrayOf(T, T, T, 0, 0, 0, 0, T, T, T, T, 0, 0, 0, 0, T, T, T),
    )

// Phone sprite (10w x 18h) — tall smartphone
private val PHONE_SPRITE =
    arrayOf(
        intArrayOf(T, 0, 0, 0, 0, 0, 0, 0, 0, T), // top rounded
        intArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 0, 0),
        intArrayOf(0, 1, 1, 0, 0, 0, 0, 1, 1, 0), // speaker slit
        intArrayOf(0, 1, 2, 2, 2, 2, 2, 2, 1, 0), // screen starts
        intArrayOf(0, 1, 2, 3, 3, 3, 3, 2, 1, 0),
        intArrayOf(0, 1, 2, 3, 2, 2, 3, 2, 1, 0),
        intArrayOf(0, 1, 2, 3, 2, 3, 3, 2, 1, 0),
        intArrayOf(0, 1, 2, 3, 3, 3, 2, 2, 1, 0),
        intArrayOf(0, 1, 2, 2, 3, 2, 3, 2, 1, 0),
        intArrayOf(0, 1, 2, 3, 2, 2, 3, 2, 1, 0),
        intArrayOf(0, 1, 2, 3, 3, 3, 3, 2, 1, 0),
        intArrayOf(0, 1, 2, 2, 2, 2, 2, 2, 1, 0), // screen ends
        intArrayOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 0),
        intArrayOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 0),
        intArrayOf(0, 1, 1, 0, 0, 0, 0, 1, 1, 0), // home button area
        intArrayOf(0, 1, 1, 0, 2, 2, 0, 1, 1, 0),
        intArrayOf(0, 1, 1, 0, 0, 0, 0, 1, 1, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // bottom
    )

// Walking figure frame 1 — left leg forward (10w x 14h)
private val WALK_FRAME_1 =
    arrayOf(
        intArrayOf(T, T, T, 0, 0, 0, 0, T, T, T), // head
        intArrayOf(T, T, 0, 0, 1, 1, 0, 0, T, T),
        intArrayOf(T, T, 0, 1, 0, 0, 1, 0, T, T),
        intArrayOf(T, T, T, 0, 0, 0, 0, T, T, T),
        intArrayOf(T, T, T, T, 0, 0, T, T, T, T), // neck
        intArrayOf(T, T, 0, 0, 0, 0, 0, 0, T, T), // torso
        intArrayOf(T, 0, 1, 0, 0, 0, 0, 1, 0, T), // arms out
        intArrayOf(T, T, T, 0, 0, 0, 0, T, T, T),
        intArrayOf(T, T, T, 0, 1, 1, 0, T, T, T),
        intArrayOf(T, T, T, 0, T, T, 0, T, T, T), // legs split
        intArrayOf(T, T, 0, T, T, T, T, 0, T, T),
        intArrayOf(T, 0, T, T, T, T, T, T, 0, T),
        intArrayOf(T, 0, 0, T, T, T, T, 0, 0, T), // feet
        intArrayOf(0, 0, 0, T, T, T, T, 0, 0, 0),
    )

// Walking figure frame 2 — right leg forward (10w x 14h)
private val WALK_FRAME_2 =
    arrayOf(
        intArrayOf(T, T, T, 0, 0, 0, 0, T, T, T), // head
        intArrayOf(T, T, 0, 0, 1, 1, 0, 0, T, T),
        intArrayOf(T, T, 0, 1, 0, 0, 1, 0, T, T),
        intArrayOf(T, T, T, 0, 0, 0, 0, T, T, T),
        intArrayOf(T, T, T, T, 0, 0, T, T, T, T), // neck
        intArrayOf(T, T, 0, 0, 0, 0, 0, 0, T, T), // torso
        intArrayOf(T, T, T, 0, 0, 0, 0, T, T, T), // arms at sides
        intArrayOf(T, 0, T, 0, 0, 0, 0, T, 0, T), // arms swing opposite
        intArrayOf(T, T, T, 0, 1, 1, 0, T, T, T),
        intArrayOf(T, T, T, T, 0, 0, T, T, T, T), // legs together
        intArrayOf(T, T, T, 0, T, T, 0, T, T, T),
        intArrayOf(T, T, 0, T, T, T, T, 0, T, T),
        intArrayOf(T, 0, 0, T, T, T, 0, 0, T, T), // feet
        intArrayOf(0, 0, 0, T, T, T, 0, 0, 0, T),
    )

@Composable
fun SyncAnimation(
    progress: Float,
    isConnecting: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "walk")

    // Walk cycle toggle (0..1, toggles sprite frame)
    val walkCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "walkCycle",
    )

    // Connecting: pace near the camera
    val connectingOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0.12f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "connecting",
    )

    // Smooth the progress jumps
    val smoothProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(500, easing = LinearEasing),
        label = "progress",
    )

    val figureProgress = if (isConnecting) connectingOffset else smoothProgress
    val walkFrame = if (walkCycle < 0.5f) WALK_FRAME_1 else WALK_FRAME_2

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(2.5f),
    ) {
        val px = size.width / 120f // pixel size: scene is 120 "pixels" wide
        val sceneHeight = (size.width / 2.5f)
        val groundY = sceneHeight * 0.82f

        // Background
        drawRect(GB_LIGHTEST)

        // Ground line
        drawRect(
            color = GB_DARKEST,
            topLeft = Offset(0f, groundY),
            size = Size(size.width, px * 1f),
        )

        // Dither pattern on ground
        for (x in 0 until 120) {
            if ((x % 2) == 0) {
                drawRect(
                    color = GB_LIGHT,
                    topLeft = Offset(x * px, groundY + px),
                    size = Size(px, px),
                )
            }
        }

        // Draw GB Camera on left (anchored at ground)
        val cameraX = 8f
        val cameraY = (groundY / px) - CAMERA_SPRITE.size
        drawSprite(CAMERA_SPRITE, cameraX, cameraY, px)

        // Draw Phone on right (anchored at ground)
        val phoneX = 120f - 10f - 8f // scene width - sprite width - margin
        val phoneY = (groundY / px) - PHONE_SPRITE.size
        drawSprite(PHONE_SPRITE, phoneX, phoneY, px)

        // Walking figure position (lerp between camera right edge and phone left edge)
        val walkStartX = cameraX + CAMERA_SPRITE[0].size + 2f
        val walkEndX = phoneX - WALK_FRAME_1[0].size - 2f
        val figureX = walkStartX + (walkEndX - walkStartX) * figureProgress
        val figureY = (groundY / px) - walkFrame.size
        drawSprite(walkFrame, figureX, figureY, px)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSprite(
    sprite: Array<IntArray>,
    offsetX: Float,
    offsetY: Float,
    pixelSize: Float,
) {
    for (row in sprite.indices) {
        for (col in sprite[row].indices) {
            val colorIdx = sprite[row][col]
            if (colorIdx == T) continue
            drawRect(
                color = PALETTE[colorIdx],
                topLeft = Offset((offsetX + col) * pixelSize, (offsetY + row) * pixelSize),
                size = Size(pixelSize, pixelSize),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SyncAnimationSyncingPreview() {
    MaterialTheme {
        SyncAnimation(
            progress = 0.4f,
            isConnecting = false,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SyncAnimationConnectingPreview() {
    MaterialTheme {
        SyncAnimation(
            progress = 0f,
            isConnecting = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SyncAnimationDonePreview() {
    MaterialTheme {
        SyncAnimation(
            progress = 1f,
            isConnecting = false,
            modifier = Modifier.padding(16.dp),
        )
    }
}
