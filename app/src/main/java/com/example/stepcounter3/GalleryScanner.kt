package com.example.stepcounter3

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.time.LocalDateTime
import java.time.ZoneId

object GalleryScanner {

    @RequiresApi(Build.VERSION_CODES.O)
    fun findPhotosInTimeRange(context: Context, startTime: LocalDateTime, endTime: LocalDateTime): List<Uri> {
        val uris = mutableListOf<Uri>()

        // Convert LocalDateTime to Milliseconds (Epoch)
        // Note: Check your ZoneId. SystemDefault is usually safest for local photos.
        val startMillis = startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - 300_000 // -5 min buffer
        val endMillis = endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 300_000   // +5 min buffer

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN
        )

        // Query: "Give me photos where DateTaken > Start AND DateTaken < End"
        val selection = "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ?"
        val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                uris.add(contentUri)
            }
        }

        return uris
    }
}