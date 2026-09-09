package com.gbcsync.app.gifmaker

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows every photo in a folder as a grid so the user can pre-select which ones to carry
 * into the GIF maker, instead of dumping the whole folder into the filmstrip there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoPreviewScreen(
    folderPath: String,
    onNavigateBack: () -> Unit,
    onConfirmSelection: (List<String>) -> Unit,
) {
    var photos by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }

    LaunchedEffect(folderPath) {
        loading = true
        photos = withContext(Dispatchers.IO) {
            File(folderPath).listFiles { f -> f.extension.equals("png", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
        selected = emptySet()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${selected.size} / ${photos.size} selected") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        selected = if (selected.size == photos.size) emptySet() else photos.indices.toSet()
                    }) {
                        Text(if (selected.size == photos.size) "Deselect all" else "Select all")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(photos.size) { index ->
                        val file = photos[index]
                        val isSelected = index in selected
                        val thumb = remember(file) { BitmapFactory.decodeFile(file.absolutePath) }
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .aspectRatio(1f)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable {
                                    selected = if (isSelected) selected - index else selected + index
                                },
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = file.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    filterQuality = FilterQuality.None,
                                    alpha = if (isSelected) 1f else 0.4f,
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .size(16.dp),
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        onConfirmSelection(selected.sorted().map { photos[it].absolutePath })
                    },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text("Use ${selected.size} photo${if (selected.size != 1) "s" else ""}")
                }
            }
        }
    }
}
