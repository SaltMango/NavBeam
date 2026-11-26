package io.github.saltmango.navbeam.dash.profile

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

data class ImportedProfilePayload(
    val profile: DashProfile,
    val sourceLabel: String,
)

object ProfileFileImporter {

    private val ALLOWED_EXTENSIONS = setOf("json", "navbeam")
    private val UNSAFE_FILENAME_CHARS = Regex("[^a-zA-Z0-9_-]")

    fun sanitizeExportFileName(profileId: String): String {
        val safeId = profileId.replace(UNSAFE_FILENAME_CHARS, "").take(64).ifBlank { "profile" }
        return "$safeId.navbeam"
    }

    fun validateSupportedExtension(fileName: String?) {
        val clean = fileName?.trim().orEmpty()
        if (clean.isEmpty()) {
            throw IllegalArgumentException(
                "Unsupported file type. Only .json and .navbeam profile files are allowed."
            )
        }
        val baseName = clean.substringAfterLast('/').substringAfterLast('\\')
        val lastDot = baseName.lastIndexOf('.')
        if (lastDot <= 0) {
            throw IllegalArgumentException(
                "Unsupported file type. Only .json and .navbeam profile files are allowed."
            )
        }
        val ext = baseName.substring(lastDot + 1).lowercase()
        if (ext !in ALLOWED_EXTENSIONS) {
            throw IllegalArgumentException(
                "Unsupported file '.$ext'. Only .json and .navbeam profile files are allowed."
            )
        }
    }

    fun importFromStream(
        stream: InputStream,
        sourceLabel: String? = null,
        reportedSizeBytes: Long? = null,
    ): Result<ImportedProfilePayload> = runCatching {
        validateSupportedExtension(sourceLabel)
        if (reportedSizeBytes != null && reportedSizeBytes > DashProfileJsonCodec.MAX_PROFILE_BYTES) {
            throw SecurityException(
                "File is too large ($reportedSizeBytes bytes). Maximum allowed size is 64 KB."
            )
        }
        val parsed = DashProfileJsonCodec.fromStream(stream).getOrThrow()
        ImportedProfilePayload(
            profile = parsed,
            sourceLabel = sourceLabel!!.trim(),
        )
    }

    fun importFromUri(contentResolver: ContentResolver, uri: Uri): Result<ImportedProfilePayload> = runCatching {
        val (displayName, reportedSize) = queryUriMetadata(contentResolver, uri)
        val resolvedName = displayName ?: uri.lastPathSegment
        validateSupportedExtension(resolvedName)
        if (reportedSize != null && reportedSize > DashProfileJsonCodec.MAX_PROFILE_BYTES) {
            throw SecurityException(
                "File is too large ($reportedSize bytes). Maximum allowed size is 64 KB."
            )
        }
        val stream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Unable to open the selected file.")
        stream.use { input ->
            importFromStream(
                stream = input,
                sourceLabel = resolvedName,
                reportedSizeBytes = reportedSize,
            ).getOrThrow()
        }
    }

    private fun queryUriMetadata(contentResolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null to null
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                name to size
            } ?: (null to null)
        }.getOrDefault(null to null)
    }
}
