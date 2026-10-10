package cn.darrenyou.doctorcam.core.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/** 位于全局页面内容之上的纯 UI 调试面板。 */
@Composable
fun DebugPanel(
    onOpenTestPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safeInsets = WindowInsets.safeDrawing
        val minX = safeInsets.getLeft(density, layoutDirection)
        val minY = safeInsets.getTop(density)
        val rightInset = safeInsets.getRight(density, layoutDirection)
        val bottomInset = safeInsets.getBottom(density)
        val viewportWidth = constraints.maxWidth
        val viewportHeight = constraints.maxHeight
        val initialOffset = with(density) {
            IntOffset(16.dp.roundToPx(), 100.dp.roundToPx())
        }

        var panelOffset by remember(density) { mutableStateOf(initialOffset) }
        var panelSize by remember { mutableStateOf(IntSize.Zero) }
        var isExpanded by remember { mutableStateOf(true) }

        fun clamp(offset: IntOffset): IntOffset = clampDebugPanelOffset(
            offset = offset,
            panelSize = panelSize,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            minX = minX,
            minY = minY,
            rightInset = rightInset,
            bottomInset = bottomInset,
        )

        LaunchedEffect(
            panelSize,
            viewportWidth,
            viewportHeight,
            minX,
            minY,
            rightInset,
            bottomInset,
            isExpanded,
        ) {
            panelOffset = clamp(panelOffset)
        }

        val dragModifier = Modifier.pointerInput(
            panelSize,
            viewportWidth,
            viewportHeight,
            minX,
            minY,
            rightInset,
            bottomInset,
        ) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                panelOffset = clamp(
                    IntOffset(
                        x = panelOffset.x + dragAmount.x.roundToInt(),
                        y = panelOffset.y + dragAmount.y.roundToInt(),
                    ),
                )
            }
        }

        Box(
            modifier = Modifier
                .zIndex(Float.MAX_VALUE)
                .wrapContentSize()
                .onSizeChanged { panelSize = it }
                .offset { panelOffset },
        ) {
            if (isExpanded) {
                ExpandedDebugPanel(
                    dragModifier = dragModifier,
                    onCollapse = { isExpanded = false },
                    onOpenTestPage = onOpenTestPage,
                )
            } else {
                CollapsedDebugPanel(
                    dragModifier = dragModifier,
                    onExpand = { isExpanded = true },
                )
            }
        }
    }
}

@Composable
private fun ExpandedDebugPanel(
    dragModifier: Modifier,
    onCollapse: () -> Unit,
    onOpenTestPage: () -> Unit,
) {
    Surface(
        modifier = Modifier.testTag("debug_panel_expanded").semantics { contentDescription = "调试面板" },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(
                start = 12.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = 12.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = dragModifier.testTag("debug_panel_drag_handle").padding(
                        horizontal = 4.dp,
                        vertical = 8.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DragIndicator,
                        contentDescription = "拖动调试面板",
                    )
                    Text(text = "Debug")
                }
                DebugTooltip(text = "折叠调试面板") {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Rounded.CloseFullscreen,
                            contentDescription = "折叠调试面板",
                        )
                    }
                }
            }
            Button(onClick = onOpenTestPage) {
                Text(text = "TestPage")
            }
        }
    }
}

@Composable
private fun CollapsedDebugPanel(
    dragModifier: Modifier,
    onExpand: () -> Unit,
) {
    Surface(
        modifier = dragModifier.testTag("debug_panel_collapsed").semantics {
            contentDescription = "已折叠的调试面板"
        },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        DebugTooltip(text = "展开调试面板") {
            IconButton(onClick = onExpand) {
                Icon(
                    imageVector = Icons.Rounded.BugReport,
                    contentDescription = "展开调试面板",
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DebugTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text(text = text) } },
        state = rememberTooltipState(),
        content = content,
    )
}

private fun clampDebugPanelOffset(
    offset: IntOffset,
    panelSize: IntSize,
    viewportWidth: Int,
    viewportHeight: Int,
    minX: Int,
    minY: Int,
    rightInset: Int,
    bottomInset: Int,
): IntOffset {
    val maxX = (viewportWidth - rightInset - panelSize.width).coerceAtLeast(minX)
    val maxY = (viewportHeight - bottomInset - panelSize.height).coerceAtLeast(minY)
    return IntOffset(
        x = offset.x.coerceIn(minX, maxX),
        y = offset.y.coerceIn(minY, maxY),
    )
}
