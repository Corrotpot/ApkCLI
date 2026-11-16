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
 * A file another app opened with this one: add it to the project's root?
 * Says so when it takes the place of a file there, and when the agent reads
 * it on every request.
 */
@Composable
fun IncomingFileSheet(
    offer: ChatViewModel.IncomingFileOffer,
    project: File,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val replaces = remember(offer, project) { File(project, offer.name).exists() }
    val body = listOfNotNull(
        stringResource(R.string.incoming_body, offer.name, bytesText(offer.bytes), project.name),
        stringResource(R.string.incoming_replaces, offer.name).takeIf { replaces },
        stringResource(R.string.incoming_instructions).takeIf { IncomingFiles.isInstructions(offer.name) },
    ).joinToString(" ")
    ConfirmSheet(
        title = stringResource(R.string.incoming_title),
        body = body,
        confirm = stringResource(if (replaces) R.string.incoming_replace else R.string.incoming_add),
        dismiss = stringResource(R.string.common_cancel),
        onConfirm = onAdd,
        onDismiss = onDismiss,
        icon = Codicons.NewFile,
        destructive = replaces,
    )
}
