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
        super(ApplicationLoader.applicationContext, "paradoks.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE deleted (acc INTEGER, dlg INTEGER, mid INTEGER, ts INTEGER, PRIMARY KEY (acc, dlg, mid))");
        db.execSQL("CREATE TABLE edits (id INTEGER PRIMARY KEY AUTOINCREMENT, acc INTEGER, dlg INTEGER, mid INTEGER, ts INTEGER, txt TEXT)");
        db.execSQL("CREATE INDEX edits_idx ON edits (acc, dlg, mid)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
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
}
