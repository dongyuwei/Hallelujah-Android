package rkr.tinykeyboard.inputmethod;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to the English dictionary SQLite database shared with the
 * macOS (hallelujahIM) and Windows (Hallelujah-Windows) versions of
 * Hallelujah:
 *
 * words_with_frequency_and_translation_and_ipa.sqlite3:
 *   words(word PRIMARY KEY, frequency, translation, ipa)
 *
 * The database is copied from assets into device-protected storage on first
 * run (device-protected so the directBootAware IME can also use it before
 * unlock) and only ever queried. The SQL statements are shared with the unit
 * tests, which run them against the same asset files through JDBC.
 */
public class DictionaryDb implements CandidateProvider.Dictionary, WordFrequencyLookup {
    // Bump when the bundled .sqlite3 assets change, so devices re-copy them.
    private static final int DB_ASSETS_VERSION = 2;
    // SQLite's default host parameter limit is 999; stay well under it.
    private static final int SQL_VARIABLE_CHUNK = 500;
    private static final String ENGLISH_DB_ASSET = "words_with_frequency_and_translation_and_ipa.sqlite3";

    static final String ENGLISH_WORDS_SQL =
            "SELECT word FROM words WHERE word LIKE ? ORDER BY frequency DESC";

    private static final DictionaryDb INSTANCE = new DictionaryDb();

    private static volatile SQLiteDatabase englishDb;
    // Vocabulary trie for the distance-3 spellcheck layer; built once on the
    // dictionary loader thread.
    private static volatile WordTrie wordTrie;

    public static DictionaryDb getInstance() {
        return INSTANCE;
    }

    public static synchronized void init(Context context) {
        if (englishDb != null) {
            return;
        }
        Context storageContext = context.createDeviceProtectedStorageContext();
        SharedPreferences prefs = storageContext.getSharedPreferences("dictionary", Context.MODE_PRIVATE);
        try {
            if (prefs.getInt("db_assets_version", -1) != DB_ASSETS_VERSION) {
                copyAssetToDatabasePath(storageContext, ENGLISH_DB_ASSET);
                prefs.edit().putInt("db_assets_version", DB_ASSETS_VERSION).apply();
            }
            englishDb = openDatabase(storageContext, ENGLISH_DB_ASSET);
            buildWordTrie(englishDb);
        } catch (IOException | android.database.SQLException e) {
            System.out.println("Hallelujah failed to open dictionary database: " + e);
        }
    }

    private static void buildWordTrie(SQLiteDatabase db) {
        WordTrie trie = new WordTrie();
        Cursor cursor = db.rawQuery("SELECT word FROM words", null);
        try {
            while (cursor.moveToNext()) {
                trie.insert(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
        wordTrie = trie;
    }

    public WordTrie getWordTrie() {
        return wordTrie;
    }

    private static SQLiteDatabase openDatabase(Context context, String name) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath(name).getAbsolutePath(),
                null, SQLiteDatabase.OPEN_READONLY);
        // Prefixes are always lowercase (all indexed words are too); this makes
        // LIKE 'x%' use the BINARY indexes instead of a full table scan.
        db.execSQL("PRAGMA case_sensitive_like = ON");
        return db;
    }

    private static void copyAssetToDatabasePath(Context context, String asset) throws IOException {
        File dbFile = context.getDatabasePath(asset);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (InputStream in = context.getAssets().open(asset);
             OutputStream out = new FileOutputStream(dbFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    @Override
    public List<String> getEnglishWords(String prefix, int limit) {
        if (englishDb == null || prefix.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> words = new ArrayList<>();
        Cursor cursor = englishDb.rawQuery(ENGLISH_WORDS_SQL + " LIMIT " + limit,
                new String[]{prefix + "%"});
        try {
            while (cursor.moveToNext()) {
                words.add(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
        return words;
    }

    @Override
    public Map<String, Long> getFrequencies(List<String> words) {
        Map<String, Long> frequencies = new HashMap<>();
        if (englishDb == null || words.isEmpty()) {
            return frequencies;
        }
        for (int start = 0; start < words.size(); start += SQL_VARIABLE_CHUNK) {
            List<String> chunk = words.subList(start, Math.min(start + SQL_VARIABLE_CHUNK, words.size()));
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                placeholders.append(i == 0 ? "?" : ",?");
            }
            Cursor cursor = englishDb.rawQuery(
                    "SELECT word, frequency FROM words WHERE word IN (" + placeholders + ")",
                    chunk.toArray(new String[0]));
            try {
                while (cursor.moveToNext()) {
                    frequencies.put(cursor.getString(0), cursor.getLong(1));
                }
            } finally {
                cursor.close();
            }
        }
        return frequencies;
    }
}
