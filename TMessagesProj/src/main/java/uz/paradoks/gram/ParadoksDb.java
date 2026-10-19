package uz.paradoks.gram;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;

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
        super(ApplicationLoader.applicationContext, "paradoks.db", null, 4);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE deleted (acc INTEGER, dlg INTEGER, mid INTEGER, ts INTEGER, PRIMARY KEY (acc, dlg, mid))");
        db.execSQL("CREATE TABLE edits (id INTEGER PRIMARY KEY AUTOINCREMENT, acc INTEGER, dlg INTEGER, mid INTEGER, ts INTEGER, txt TEXT)");
        db.execSQL("CREATE INDEX edits_idx ON edits (acc, dlg, mid)");
        createMediaTable(db);
        createPendingTable(db);
        createForwardTable(db);
    }

    private static void createForwardTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS forwards (acc INTEGER, src_dlg INTEGER, src_mid INTEGER, tgt_dlg INTEGER, tgt_mid INTEGER, PRIMARY KEY (acc, src_dlg, src_mid))");
    }

    private static void createPendingTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pending_media (acc INTEGER, dlg INTEGER, mid INTEGER, ts INTEGER, PRIMARY KEY (acc, dlg, mid))");
    }

    private static void createMediaTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_media (acc INTEGER, dlg INTEGER, mid INTEGER, path TEXT, kind INTEGER, PRIMARY KEY (acc, dlg, mid))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createMediaTable(db);
        }
        if (oldVersion < 3) {
            createPendingTable(db);
        }
        if (oldVersion < 4) {
            createForwardTable(db);
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

    /** Media that still has to be copied (survives app restarts). */
    void insertPending(int acc, long dlg, int mid) {
        ContentValues values = new ContentValues();
        values.put("acc", acc);
        values.put("dlg", dlg);
        values.put("mid", mid);
        values.put("ts", System.currentTimeMillis() / 1000);
        getWritableDatabase().insertWithOnConflict("pending_media", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    void deletePending(int acc, long dlg, int mid) {
        getWritableDatabase().delete("pending_media", "acc = ? AND dlg = ? AND mid = ?",
                new String[]{String.valueOf(acc), String.valueOf(dlg), String.valueOf(mid)});
    }

    void purgePending(long olderThanTs) {
        getWritableDatabase().delete("pending_media", "ts < ?", new String[]{String.valueOf(olderThanTs)});
    }

    /** Returns rows of {acc, dlg, mid}. */
    ArrayList<long[]> queryPending() {
        ArrayList<long[]> result = new ArrayList<>();
        android.database.Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT acc, dlg, mid FROM pending_media", null);
            while (cursor.moveToNext()) {
                result.add(new long[]{cursor.getInt(0), cursor.getLong(1), cursor.getInt(2)});
            }
        } catch (Throwable ignore) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    /** Remembers where the database copy of a message lives. */
    void insertForward(int acc, long srcDlg, int srcMid, long tgtDlg, int tgtMid) {
        ContentValues values = new ContentValues();
        values.put("acc", acc);
        values.put("src_dlg", srcDlg);
        values.put("src_mid", srcMid);
        values.put("tgt_dlg", tgtDlg);
        values.put("tgt_mid", tgtMid);
        getWritableDatabase().insertWithOnConflict("forwards", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Returns {tgtDlg, tgtMid} or null. */
    long[] queryForward(int acc, long srcDlg, int srcMid) {
        android.database.Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT tgt_dlg, tgt_mid FROM forwards WHERE acc = ? AND src_dlg = ? AND src_mid = ?",
                    new String[]{String.valueOf(acc), String.valueOf(srcDlg), String.valueOf(srcMid)});
            if (cursor.moveToFirst()) {
                return new long[]{cursor.getLong(0), cursor.getLong(1)};
            }
        } catch (Throwable ignore) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /** Rows of {acc, dlg, mid} plus the path (parallel list). */
    ArrayList<Object[]> queryAllMedia() {
        ArrayList<Object[]> result = new ArrayList<>();
        android.database.Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT acc, dlg, mid, path FROM deleted_media", null);
            while (cursor.moveToNext()) {
                result.add(new Object[]{cursor.getInt(0), cursor.getLong(1), cursor.getInt(2), cursor.getString(3)});
            }
        } catch (Throwable ignore) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    void deleteMedia(int acc, long dlg, int mid) {
        getWritableDatabase().delete("deleted_media", "acc = ? AND dlg = ? AND mid = ?",
                new String[]{String.valueOf(acc), String.valueOf(dlg), String.valueOf(mid)});
    }

    /** Edited messages, newest first: rows of {dlg, mid, lastTs, versions, lastEditRowId}. */
    ArrayList<long[]> queryEditGroups(int acc) {
        ArrayList<long[]> result = new ArrayList<>();
        android.database.Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT dlg, mid, MAX(ts), COUNT(*), MAX(id) FROM edits WHERE acc = ? GROUP BY dlg, mid ORDER BY MAX(id) DESC LIMIT 200",
                    new String[]{String.valueOf(acc)});
            while (cursor.moveToNext()) {
                result.add(new long[]{cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3), cursor.getLong(4)});
            }
        } catch (Throwable ignore) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    String editText(long rowId) {
        android.database.Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT txt FROM edits WHERE id = ?", new String[]{String.valueOf(rowId)});
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
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
