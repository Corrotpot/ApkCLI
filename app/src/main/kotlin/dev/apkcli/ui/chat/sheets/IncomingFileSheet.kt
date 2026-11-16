package dev.apkcli.ui.chat.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dev.apkcli.R
import dev.apkcli.ui.chat.ChatViewModel
import dev.apkcli.ui.chat.IncomingFiles
import dev.apkcli.ui.components.ConfirmSheet
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.text.bytesText
import java.io.File

/**
 * Files opened with this app, or picked from the device in the Files tab:
 * add them to the project? Says which files, how large, into which folder,
 * which of them take the place of files there, and when the agent reads one
 * on every request.
 */
@Composable
fun IncomingFileSheet(
    offer: ChatViewModel.IncomingFileOffer,
    project: File,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val into = remember(offer, project) { IncomingFiles.intoFolder(offer.folder, project) }
    val atRoot = remember(into, project) { into == IncomingFiles.intoFolder(null, project) }
    val names = offer.files.map { it.name }
    val replaced = remember(offer, into) { names.filter { File(into, it).isFile } }

    val shown = IncomingFiles.NAMES_SHOWN
    val listed = names.take(shown).joinToString(", ") +
        if (names.size > shown) " " + stringResource(R.string.incoming_more, names.size - shown) else ""
    val body = listOfNotNull(
        "$listed (${bytesText(offer.bytes)})",
        if (atRoot) {
            stringResource(R.string.incoming_into_root, project.name)
        } else {
            stringResource(R.string.incoming_into_folder, IncomingFiles.folderLabel(into, project))
        },
        when {
            replaced.isEmpty() -> null
            names.size == 1 -> stringResource(R.string.incoming_replaces, replaced.first())
            else -> stringResource(R.string.incoming_replaces_many, IncomingFiles.namesText(replaced))
        },
        stringResource(R.string.incoming_instructions).takeIf { atRoot && names.any(IncomingFiles::isInstructions) },
    ).joinToString("\n")

    ConfirmSheet(
        title = stringResource(if (names.size == 1) R.string.incoming_title else R.string.incoming_title_many),
        body = body,
        confirm = stringResource(if (replaced.isNotEmpty()) R.string.incoming_replace else R.string.incoming_add),
        dismiss = stringResource(R.string.common_cancel),
        onConfirm = onAdd,
        onDismiss = onDismiss,
        icon = Codicons.NewFile,
        destructive = replaced.isNotEmpty(),
    )
}
