package com.camcompiler.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "cam_compiler_prefs")

object Prefs {
    private val LAST_FOLDER_KEY = stringPreferencesKey("last_folder_uri")

    suspend fun getLastFolder(ctx: Context): String? {
        return ctx.dataStore.data.map { it[LAST_FOLDER_KEY] }.first()
    }

    suspend fun setLastFolder(ctx: Context, uri: String) {
        ctx.dataStore.edit { it[LAST_FOLDER_KEY] = uri }
    }

    suspend fun clearLastFolder(ctx: Context) {
        ctx.dataStore.edit { it.remove(LAST_FOLDER_KEY) }
    }
}
