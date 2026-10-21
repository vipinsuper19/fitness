package com.vipin.fitness.feature.auth

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.designsystem.ExportTags

/** Share sheet for the export: ACTION_SEND, application/json, content:// with a read grant. */
fun exportShareIntent(file: Uri, title: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, file)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

@Composable
fun ExportRoute(viewModel: ExportViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.export_share_title)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), viewModel::saveTo)
    LaunchedEffect(s.shareRequested) {
        val file = s.file
        if (s.shareRequested && file != null) {
            viewModel.shareShown()
            context.startActivity(exportShareIntent(file, shareTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    ExportContent(s, onStart = viewModel::export, onShare = viewModel::share, onSave = { save.launch(viewModel.suggestedName) })
}

@Composable
fun ExportContent(s: ExportState, onStart: () -> Unit, onShare: () -> Unit, onSave: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag(ExportTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.export_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.export_body))
        Button(onClick = onStart, enabled = !s.loading, modifier = Modifier.fillMaxWidth().testTag(ExportTags.START)) {
            Text(stringResource(R.string.export_start))
        }
        if (s.loading) CircularProgressIndicator(Modifier.testTag(ExportTags.PROGRESS))
        s.error?.let { e ->
            Text(
                stringResource(
                    when (e) {
                        ExportError.RATE_LIMITED -> R.string.export_rate_limited
                        ExportError.OFFLINE -> R.string.export_offline
                        ExportError.FAILED -> R.string.export_failed
                        ExportError.SAVE_FAILED -> R.string.export_save_failed
                    },
                ),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(ExportTags.ERROR),
            )
            if (e != ExportError.SAVE_FAILED || s.file == null) {
                OutlinedButton(onClick = onStart, enabled = !s.loading, modifier = Modifier.testTag(ExportTags.RETRY)) {
                    Text(stringResource(R.string.export_retry))
                }
            }
        }
        if (s.file != null && !s.loading) {
            Text(
                stringResource(if (s.saved) R.string.export_saved else R.string.export_ready),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(ExportTags.SUCCESS),
            )
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth().testTag("export_share")) { Text(stringResource(R.string.export_share)) }
            OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth().testTag("export_save")) { Text(stringResource(R.string.export_save)) }
        }
        Text(stringResource(R.string.export_privacy_note), style = MaterialTheme.typography.bodySmall)
    }
}
