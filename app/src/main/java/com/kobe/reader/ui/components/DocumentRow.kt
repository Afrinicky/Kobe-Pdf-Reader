package com.kobe.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobe.reader.R
import com.kobe.reader.core.common.asFileSize
import com.kobe.reader.core.common.asRelativeTime
import com.kobe.reader.core.file.DocumentRef

/** What a row's overflow menu can offer. Origin decides which are shown. */
data class DocumentActions(
    val onOpen: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onShare: () -> Unit,
    val onRename: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    val onOrganize: () -> Unit,
    val onDetails: () -> Unit,
)

/**
 * One document in a list.
 *
 * Layout follows Acrobat's file list: a red PDF glyph, filename on one line
 * elided at the end, then a quiet metadata line, then a star and an overflow.
 */
@Composable
fun DocumentRow(
    document: DocumentRef,
    actions: DocumentActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clickable(onClick = actions.onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PdfGlyph()

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = document.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildMetadataLine(
                    document = document,
                    size = document.sizeBytes.asFileSize(context),
                    time = (
                        document.lastOpenedAt.takeIf { it > 0 } ?: document.lastModified
                        ).asRelativeTime(context),
                    pagesLabel = document.pageCount
                        .takeIf { it > 0 }
                        ?.let { count ->
                            if (count == 1) {
                                stringResource(R.string.page_count_one)
                            } else {
                                stringResource(R.string.pages_count, count)
                            }
                        },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = actions.onToggleFavorite) {
            Icon(
                painter = painterResource(
                    if (document.isFavorite) R.drawable.ic_star else R.drawable.ic_star_border,
                ),
                contentDescription = stringResource(
                    if (document.isFavorite) R.string.action_unfavorite else R.string.action_favorite,
                ),
                tint = if (document.isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.cd_more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DocumentMenu(
                expanded = menuExpanded,
                document = document,
                actions = actions,
                onDismiss = { menuExpanded = false },
            )
        }
    }
}

@Composable
private fun DocumentMenu(
    expanded: Boolean,
    document: DocumentRef,
    actions: DocumentActions,
    onDismiss: () -> Unit,
) {
    // A one-off ACTION_OPEN_DOCUMENT pick is usually read-only. Offering rename
    // and delete there would mean showing an action that reliably fails, so the
    // menu adapts to what the document's origin actually permits.
    val canModify = document.origin != DocumentRef.Origin.Picked

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuAction(R.string.action_open, R.drawable.ic_page) { onDismiss(); actions.onOpen() }
        MenuAction(R.string.tool_reorder, R.drawable.ic_organize) {
            onDismiss(); actions.onOrganize()
        }
        MenuAction(R.string.action_share, R.drawable.ic_share) { onDismiss(); actions.onShare() }
        MenuAction(R.string.action_duplicate, R.drawable.ic_extract) {
            onDismiss(); actions.onDuplicate()
        }
        if (canModify) {
            MenuAction(R.string.action_rename, R.drawable.ic_edit) {
                onDismiss(); actions.onRename()
            }
            MenuAction(R.string.action_delete, R.drawable.ic_delete) {
                onDismiss(); actions.onDelete()
            }
        }
        MenuAction(R.string.action_details, R.drawable.ic_info) {
            onDismiss(); actions.onDetails()
        }
    }
}

@Composable
private fun MenuAction(labelRes: Int, iconRes: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        leadingIcon = { Icon(painterResource(iconRes), contentDescription = null) },
        onClick = onClick,
    )
}

/** The red document mark. Acrobat's most recognisable single element. */
@Composable
fun PdfGlyph(size: Int = 40, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_pdf),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size((size * 0.55f).dp),
        )
    }
}

private fun buildMetadataLine(
    document: DocumentRef,
    size: String,
    time: String,
    pagesLabel: String?,
): String = listOfNotNull(
    pagesLabel,
    size.takeIf { document.sizeBytes > 0 },
    time.takeIf { it.isNotBlank() },
).joinToString("  ·  ")

/** A row placeholder shown while the library is loading. */
@Composable
fun DocumentRowPlaceholder(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = "Loading" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .size(width = 180.dp, height = 14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Box(
                Modifier
                    .size(width = 110.dp, height = 11.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
    }
}
