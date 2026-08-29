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
import java.util.List;

/**
 * Read-only access to the dictionary SQLite databases shared with the macOS
 * (hallelujahIM) and Windows (Hallelujah-Windows) versions of Hallelujah:
 *
 * words_with_frequency_and_translation_and_ipa.sqlite3:
 *   words(word PRIMARY KEY, frequency, translation, ipa)
 * pinyin_data.sqlite3:
 *   pinyin_data(id, hz, py, abbr, freq), indexed on py and abbr
 *
 * The databases are copied from assets into device-protected storage on first
 * run (device-protected so the directBootAware IME can also use them before
 * unlock) and only ever queried. The SQL statements are shared with the unit
 * tests, which run them against the same asset files through JDBC.
 */
public class DictionaryDb implements CandidateProvider.Dictionary {
    // Bump when the bundled .sqlite3 assets change, so devices re-copy them.
    private static final int DB_ASSETS_VERSION = 1;
    private static final String ENGLISH_DB_ASSET = "words_with_frequency_and_translation_and_ipa.sqlite3";
    private static final String PINYIN_DB_ASSET = "pinyin_data.sqlite3";

    static final String ENGLISH_WORDS_SQL =
            "SELECT word FROM words WHERE word LIKE ? ORDER BY frequency DESC";
    static final String HANZI_BY_PINYIN_SQL =
            "SELECT hz FROM pinyin_data WHERE py LIKE ? OR abbr LIKE ?"
                    + " ORDER BY CASE WHEN py = ? OR abbr = ? THEN 0 ELSE 1 END, freq DESC";

    private static final DictionaryDb INSTANCE = new DictionaryDb();

    private static volatile SQLiteDatabase englishDb;
    private static volatile SQLiteDatabase pinyinDb;

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
                copyAssetToDatabasePath(storageContext, PINYIN_DB_ASSET);
                prefs.edit().putInt("db_assets_version", DB_ASSETS_VERSION).apply();
            }
            englishDb = openDatabase(storageContext, ENGLISH_DB_ASSET);
            pinyinDb = openDatabase(storageContext, PINYIN_DB_ASSET);
        } catch (IOException | android.database.SQLException e) {
            System.out.println("Hallelujah failed to open dictionary database: " + e);
        }
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
    public List<String> getHanZiByPinyin(String prefix, int limit) {
        if (pinyinDb == null || prefix.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> hanziList = new ArrayList<>();
        Cursor cursor = pinyinDb.rawQuery(HANZI_BY_PINYIN_SQL + " LIMIT " + limit,
                new String[]{prefix + "%", prefix + "%", prefix, prefix});
        try {
            while (cursor.moveToNext()) {
                hanziList.add(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
        return hanziList;
    }
}
