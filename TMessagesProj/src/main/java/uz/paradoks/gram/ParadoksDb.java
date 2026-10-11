package uz.paradoks.gram;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.ApplicationLoader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local ParadoksGram database: deleted-message marks and edit history. */
final class ParadoksDb extends SQLiteOpenHelper {

    private static ParadoksDb instance;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    static synchronized ParadoksDb get() {
        if (instance == null) {
            instance = new ParadoksDb();
        }
        return instance;
    }

    /** Runs a write on a background thread so Telegram's queues are never blocked. */
    static void async(Runnable runnable) {
        IO.execute(() -> {
            try {
                runnable.run();
            } catch (Throwable ignore) {
            }
        });
    }

    private ParadoksDb() {
        super(ApplicationLoader.applicationContext, "paradoks.db", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE deleted (acc INTEGER, dlg INTEGER, mid INTEGER, ts INTEGER, PRIMARY KEY (acc, dlg, mid))");
        db.execSQL("CREATE TABLE edits (id INTEGER PRIMARY KEY AUTOINCREMENT, acc INTEGER, dlg INTEGER, mid INTEGER, ts INTEGER, txt TEXT)");
        db.execSQL("CREATE INDEX edits_idx ON edits (acc, dlg, mid)");
        createMediaTable(db);
    }

    private static void createMediaTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_media (acc INTEGER, dlg INTEGER, mid INTEGER, path TEXT, kind INTEGER, PRIMARY KEY (acc, dlg, mid))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createMediaTable(db);
        }
    }

    void insertEdit(int acc, long dlg, int mid, long ts, String text) {
        ContentValues values = new ContentValues();
        values.put("acc", acc);
        values.put("dlg", dlg);
        values.put("mid", mid);
        values.put("ts", ts);
        values.put("txt", text);
        getWritableDatabase().insert("edits", null, values);
    }

    void insertDeleted(int acc, long dlg, int mid) {
        ContentValues values = new ContentValues();
        values.put("acc", acc);
        values.put("dlg", dlg);
        values.put("mid", mid);
        values.put("ts", System.currentTimeMillis() / 1000);
        getWritableDatabase().insertWithOnConflict("deleted", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void insertMedia(int acc, long dlg, int mid, String path, int kind) {
        ContentValues values = new ContentValues();
        values.put("acc", acc);
        values.put("dlg", dlg);
        values.put("mid", mid);
        values.put("path", path);
        values.put("kind", kind);
        getWritableDatabase().insertWithOnConflict("deleted_media", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Returns {path, kind} of the saved media or null. */
    String[] queryMedia(int acc, long dlg, int mid) {
        android.database.Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT path, kind FROM deleted_media WHERE acc = ? AND dlg = ? AND mid = ?",
                    new String[]{String.valueOf(acc), String.valueOf(dlg), String.valueOf(mid)});
            if (cursor.moveToFirst()) {
                return new String[]{cursor.getString(0), String.valueOf(cursor.getInt(1))};
            }
        } catch (Throwable ignore) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }
}
