package dev.framio.pomodoro.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.model.Task

/** "+ New": a task title on the built-in keyboard (or the system IME, if chosen in Settings). Home and the ritual. */
@Composable
fun TaskDraft(
    draft: String,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KeyboardSheet(
        title = stringResource(R.string.what_work_on),
        value = draft,
        onValueChange = onEdit,
        onDone = onAdd,
        onCancel = onCancel,
        modifier = modifier,
        maxLength = Task.MAX_TITLE,
        doneLabel = stringResource(R.string.add),
        hint = stringResource(R.string.new_task_hint),
    )
}
