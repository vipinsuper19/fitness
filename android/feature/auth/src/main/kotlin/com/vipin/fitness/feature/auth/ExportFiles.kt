package com.vipin.fitness.feature.auth

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.vipin.fitness.core.data.UserScopedState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Where an export goes on the device. */
interface ExportFiles {
    /** Writes [json] to a fresh shareable file and returns its content:// uri. Older exports are removed first. */
    suspend fun write(json: String): Uri
    /** Copies the export at [from] to a document the user picked ([to], from ACTION_CREATE_DOCUMENT). */
    suspend fun copy(from: Uri, to: Uri)
    fun suggestedName(): String
}

/**
 * Keeps at most one export, in cache/exports (the only folder the FileProvider exposes). The
 * export is personal data, so older ones are deleted on each export rather than piling up, and
 * the folder is wiped on sign-out and account deletion (both run SessionRepository.signOut,
 * which clears every [UserScopedState]) and when another account signs in.
 */
@Singleton
class CacheExportFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : ExportFiles, UserScopedState {
    private val dir get() = File(context.cacheDir, "exports")

    override fun suggestedName() = "fitness-export-${LocalDate.now(clock)}.json"

    override suspend fun write(json: String): Uri = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, suggestedName()).apply { writeText(json) }
        FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    }

    override suspend fun clearUserState() {
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
    }

    override suspend fun copy(from: Uri, to: Uri) = withContext(Dispatchers.IO) {
        val r = context.contentResolver
        checkNotNull(r.openInputStream(from)).use { input -> checkNotNull(r.openOutputStream(to)).use { input.copyTo(it) } }
        Unit
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ExportFilesModule {
    @Binds abstract fun exportFiles(impl: CacheExportFiles): ExportFiles
    @Binds @IntoSet abstract fun exportFilesUserState(impl: CacheExportFiles): UserScopedState
}
