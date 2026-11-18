package io.github.gengar93.spendidly.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.gengar93.spendidly.backend.ExpenseItem
import kotlinx.coroutines.delay
import java.time.LocalDate

private val bouncy = spring<Dp>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)

/** Corner radius that springs to [pressed] while the element is held. */
@Composable
fun morphRadius(interaction: MutableInteractionSource, rest: Dp, pressed: Dp): Dp {
    val down by interaction.collectIsPressedAsState()
    return animateDpAsState(if (down) pressed else rest, bouncy, label = "morph").value
}

/** A category's expressive shape filled with its tonal container, glyph on top. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CategoryTile(name: String?, size: Dp = 42.dp, inverted: Boolean = false) {
    val style = categoryStyle(name)
    val (container, content) = roleColors(style.role)
    Box(
        Modifier.size(size).clip(style.shape.toShape()).background(if (inverted) content else container),
        contentAlignment = Alignment.Center,
    ) { Icon(style.icon, contentDescription = null, tint = if (inverted) container else content, modifier = Modifier.size(size * 0.48f)) }
}

/**
 * One row of a segmented list: rows in a group sit 2dp apart, the first and last carry the
 * group's large corners, and every row springs to fully rounded while pressed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SegmentedItem(
    index: Int, count: Int, modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null,
    verticalPadding: Dp = 12.dp, selected: Boolean = false,
    gestures: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val big = 22.dp
    val small = 6.dp
    val top = morphRadius(interaction, if (index == 0 || selected) big else small, big)
    val bottom = morphRadius(interaction, if (index == count - 1 || selected) big else small, big)
    val shape = RoundedCornerShape(top, top, bottom, bottom)
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else p.surface)
            .then(
                if (onClick != null) Modifier.combinedClickable(
                    interactionSource = interaction, indication = ripple(), role = SemanticsRole.Button,
                    onClick = onClick, onLongClick = onLongClick,
                ) else Modifier,
            )
            // Inside the click handling, so a gesture that consumes the release (e.g. a drag) cancels the tap.
            .then(gestures)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun SegmentedColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

/** An [ExpenseRow]'s height with its 2dp group gap: 12dp padding either side of the 42dp category tile. */
val ExpenseRowHeight = 68.dp

@Composable
fun ExpenseRow(
    item: ExpenseItem, index: Int, count: Int,
    onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    SegmentedItem(index, count, onClick = onClick, onLongClick = onLongClick) {
        CategoryTile(item.categoryName)
        Column(Modifier.weight(1f)) {
            Text(item.note ?: item.categoryName, style = Type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.note != null) Text(item.categoryName, style = Type.small, color = p.sub, maxLines = 1)
        }
        Text(rupees(item.amountPaise), style = Type.rowAmount, color = p.ink)
    }
}

/** A day: label and total above, its entries as one segmented group. */
@Composable
fun DayGroup(
    date: LocalDate, today: LocalDate, items: List<ExpenseItem>, modifier: Modifier = Modifier,
    onClick: ((ExpenseItem) -> Unit)? = null,
) {
    val p = LocalPalette.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 8.dp)) {
            Text(dayLabel(date, today), style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = p.ink, modifier = Modifier.weight(1f))
            Text(rupees(items.sumOf { it.amountPaise }), style = Type.small, color = p.sub)
        }
        SegmentedColumn {
            items.forEachIndexed { i, item -> ExpenseRow(item, i, items.size, onClick?.let { { it(item) } }) }
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.line))
}

/** Round icon button that squares off while pressed. */
@Composable
fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val r = morphRadius(interaction, 22.dp, 14.dp)
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(r)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interaction, ripple(), onClickLabel = label, role = SemanticsRole.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) }
}

@Composable
fun ScreenTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.title, color = LocalPalette.current.ink, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Floating toolbar: the current screen's button fills with primary and shows its name. FAB sits apart. */
@Composable
fun Dock(current: Screen, onSelect: (Screen) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.shadow(8.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(c.surfaceContainerHigh).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((screen, icon) in listOf(Screen.Summary to Glyph.Summary, Screen.History to Glyph.History, Screen.Ask to Glyph.Ask)) {
                val on = screen == current
                val start by animateDpAsState(if (on) 14.dp else 12.dp, bouncy, label = "pad")
                val end by animateDpAsState(if (on) 18.dp else 12.dp, bouncy, label = "pad")
                Row(
                    Modifier.height(48.dp).clip(RoundedCornerShape(50)).background(if (on) c.primary else Color.Transparent)
                        .clickable(role = SemanticsRole.Tab, onClickLabel = screen.name) { onSelect(screen) }
                        .padding(start = start, end = end),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = screen.name, tint = if (on) c.onPrimary else c.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    if (on) {
                        Spacer(Modifier.width(8.dp))
                        Text(screen.name, style = Type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.onPrimary)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val interaction = remember { MutableInteractionSource() }
        val r = morphRadius(interaction, 20.dp, 32.dp)
        Box(
            Modifier.size(64.dp).shadow(8.dp, RoundedCornerShape(r)).clip(RoundedCornerShape(r)).background(c.primaryContainer)
                .clickable(interaction, ripple(), onClickLabel = "Add expense", role = SemanticsRole.Button, onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Add, contentDescription = "Add expense", tint = c.onPrimaryContainer, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
fun ToastHost(toast: Toast?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    LaunchedEffect(toast?.id) {
        if (toast != null) { delay(if (toast.undo != null) 5000 else 3000); onDismiss() }
    }
    AnimatedVisibility(toast != null, modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        val t = toast ?: return@AnimatedVisibility
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.inverseSurface)
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t.text, style = Type.small, color = c.inverseOnSurface, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (t.undo != null) {
                Text(
                    "Undo", style = Type.label, color = c.inversePrimary,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { t.undo.invoke(); onDismiss() }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Filter chip: square-ish at rest, rounds and fills with secondary container when selected. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val r by animateDpAsState(if (selected) 16.dp else 8.dp, bouncy, label = "chip")
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(r))
            .background(if (selected) c.secondaryContainer else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else c.outlineVariant, RoundedCornerShape(r))
            .clickable(role = SemanticsRole.Button, onClick = onClick).padding(start = if (leading != null) 6.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(text, style = Type.label, color = if (selected) c.onSecondaryContainer else c.onSurface)
    }
}

/** Connected button group: one choice, the selected segment fills and fully rounds. */
@Composable
fun ConnectedGroup(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val outer by animateDpAsState(20.dp, bouncy, label = "g")
            val inner by animateDpAsState(if (on) 20.dp else 6.dp, bouncy, label = "g")
            val start = if (i == 0) outer else inner
            val end = if (i == options.lastIndex) outer else inner
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(start, end, end, start))
                    .background(if (on) c.primary else c.surfaceContainerHigh)
                    .clickable(role = SemanticsRole.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = Type.label, color = if (on) c.onPrimary else c.onSurface) }
        }
    }
}
