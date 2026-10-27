package com.music.spotui.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Myspoti's sheet of the screens shared by its two looks. MySpotilol has the Classic look only:
 * Material's [ModalBottomSheet] on [containerColor], so Myspoti's sheets port unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) = ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, containerColor = containerColor, content = content)
