package com.example.security;

import android.content.SharedPreferences;

import java.util.Map;

/**
 * Preserves the synchronous boolean acknowledgement required by secure callers.
 * The Kotlin KTX edit extension intentionally returns Unit and cannot expose commit().
 */
public final class SharedPreferencesCommitter {
    private SharedPreferencesCommitter() {
    }

    public static boolean commit(
            SharedPreferences preferences,
            Map<String, String> values,
            Iterable<String> removals
    ) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : removals) {
            editor.remove(key);
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            editor.putString(entry.getKey(), entry.getValue());
        }
        return editor.commit();
    }
}
