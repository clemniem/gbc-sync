package com.gbcsync.app.gifmaker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteMakerScreen(
    onNavigateBack: () -> Unit,
    onSavePalette: (GbPalette) -> Unit,
    savedPalettes: List<GbPalette> = emptyList(),
    onDeletePalette: (String) -> Unit = {},
) {
    val context = LocalContext.current

    var paletteName by remember { mutableStateOf("") }
    var paletteShortName by remember { mutableStateOf("") }

    // Store colors as HSL (source of truth) to avoid drift when adjusting sliders
    // Each entry: [hue 0-1, saturation 0-1, lightness 0-1]
    var hsl0 by remember { mutableStateOf(floatArrayOf(0f, 0f, 1f)) }    // white
    var hsl1 by remember { mutableStateOf(floatArrayOf(0f, 0f, 0.67f)) } // light gray
    var hsl2 by remember { mutableStateOf(floatArrayOf(0f, 0f, 0.33f)) } // dark gray
    var hsl3 by remember { mutableStateOf(floatArrayOf(0f, 0f, 0f)) }    // black

    var activeColorIndex by remember { mutableIntStateOf(0) }

    var sourceImageUri by remember { mutableStateOf<Uri?>(null) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        sourceImageUri = uri
        if (uri != null) {
            sourceBitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        }
    }

    fun getHsl(index: Int) = when (index) {
        0 -> hsl0; 1 -> hsl1; 2 -> hsl2; 3 -> hsl3; else -> hsl0
    }

    fun setHsl(index: Int, value: FloatArray) {
        when (index) {
            0 -> hsl0 = value
            1 -> hsl1 = value
            2 -> hsl2 = value
            3 -> hsl3 = value
        }
    }

    fun colorToHsl(color: Int): FloatArray {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f)) / 6f
            g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        return floatArrayOf(h, s, l)
    }

    fun hslToColor(h: Float, s: Float, l: Float): Int {
        if (s == 0f) {
            val v = (l * 255).toInt().coerceIn(0, 255)
            return 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        fun hue2rgb(p: Float, q: Float, t: Float): Float {
            val tt = if (t < 0) t + 1 else if (t > 1) t - 1 else t
            return when {
                tt < 1f / 6 -> p + (q - p) * 6f * tt
                tt < 1f / 2 -> q
                tt < 2f / 3 -> p + (q - p) * (2f / 3 - tt) * 6f
                else -> p
            }
        }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        val r = (hue2rgb(p, q, h + 1f / 3) * 255).toInt().coerceIn(0, 255)
        val g = (hue2rgb(p, q, h) * 255).toInt().coerceIn(0, 255)
        val b = (hue2rgb(p, q, h - 1f / 3) * 255).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    fun colorFromHsl(hsl: FloatArray) = hslToColor(hsl[0], hsl[1], hsl[2])
    fun getColors() = listOf(colorFromHsl(hsl0), colorFromHsl(hsl1), colorFromHsl(hsl2), colorFromHsl(hsl3))
    fun activeColor() = colorFromHsl(getHsl(activeColorIndex))
    fun activeHsl() = getHsl(activeColorIndex)

    fun updateHsl(hue: Float = activeHsl()[0], sat: Float = activeHsl()[1], light: Float = activeHsl()[2]) {
        setHsl(activeColorIndex, floatArrayOf(hue, sat, light))
    }

    fun toHex(color: Int): String = "#%06x".format(color and 0x00FFFFFF)

    fun exportJson(): String {
        val colors = getColors().joinToString(", ") { "\"${toHex(it)}\"" }
        return """{"shortName": "${paletteShortName.ifEmpty { "custom" }}", "name": "${paletteName.ifEmpty { "Custom Palette" }}", "palette": [$colors]}"""
    }

    // Live preview bitmap
    val previewBitmap = remember(sourceBitmap, hsl0, hsl1, hsl2, hsl3) {
        val src = sourceBitmap ?: return@remember null
        GifEncoder.remapBitmap(src, getColors())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Palette Maker") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Image preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(128f / 112f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .clickable {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap to pick an image",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4 color buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                getColors().forEachIndexed { index, color ->
                    val isActive = index == activeColorIndex
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { activeColorIndex = index },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color(color))
                                .then(
                                    if (isActive) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary)
                                    } else {
                                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline)
                                    },
                                ),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            toHex(color),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hue + Saturation + Brightness sliders
            val curHsl = activeHsl()

            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Hue", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = curHsl[0],
                    onValueChange = { updateHsl(hue = it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(hslToColor(curHsl[0], 1f, 0.5f)),
                        activeTrackColor = Color(hslToColor(curHsl[0], 1f, 0.5f)),
                    ),
                )

                Text("Saturation", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = curHsl[1],
                    onValueChange = { updateHsl(sat = it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(hslToColor(curHsl[0], curHsl[1], 0.5f)),
                        activeTrackColor = Color(hslToColor(curHsl[0], curHsl[1], 0.5f)),
                    ),
                )

                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = curHsl[2],
                    onValueChange = { updateHsl(light = it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(activeColor()),
                        activeTrackColor = Color(activeColor()),
                    ),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Name fields
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = paletteName,
                    onValueChange = { paletteName = it },
                    label = { Text("Name") },
                    modifier = Modifier.weight(2f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = paletteShortName,
                    onValueChange = { paletteShortName = it },
                    label = { Text("Short") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val palette = GbPalette(
                            shortName = paletteShortName.ifEmpty { "custom" },
                            name = paletteName.ifEmpty { "Custom Palette" },
                            colors = getColors(),
                        )
                        onSavePalette(palette)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save")
                }
                Button(
                    onClick = {
                        val json = exportJson()
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_TEXT, json)
                        }
                        context.startActivity(Intent.createChooser(intent, "Export Palette"))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export JSON")
                }
            }

            // Saved palettes
            if (savedPalettes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Saved Palettes", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                savedPalettes.forEach { palette ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Color swatches
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                palette.colors.forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(Color(color)),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(palette.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    palette.shortName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Load into editor
                            IconButton(onClick = {
                                paletteName = palette.name
                                paletteShortName = palette.shortName
                                palette.colors.forEachIndexed { i, color ->
                                    val h = colorToHsl(color)
                                    setHsl(i, h)
                                }
                            }) {
                                Icon(Icons.Default.Edit, "Edit")
                            }
                            // Export
                            IconButton(onClick = {
                                val colors = palette.colors.joinToString(", ") { "\"${toHex(it)}\"" }
                                val json = """{"shortName": "${palette.shortName}", "name": "${palette.name}", "palette": [$colors]}"""
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_TEXT, json)
                                }
                                context.startActivity(Intent.createChooser(intent, "Export Palette"))
                            }) {
                                Icon(Icons.Default.Share, "Export")
                            }
                            // Delete
                            IconButton(onClick = { onDeletePalette(palette.shortName) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    "Delete",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
