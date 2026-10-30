package uz.paradoks.gram;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaController;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * In-app viewer for PDF, Word (.docx) and Excel (.xlsx, .xls) files, so no external app is needed.
 * It is called from Telegram's "open file" code; when it declines (setting off, other file type,
 * restricted open, too large), the normal "open with another app" flow runs as before.
 *
 * Opening a document also copies it to Downloads/Telegram once (setting), except in chats where
 * saving is restricted: those files are shown but never copied.
 */
public final class ParadoksDocViewer {

    private static final long MAX_SIZE = 100L * 1024 * 1024;
    private static final String PREFS = "paradoks_docs";
    private static final String KEY_SAVED = "saved";
    private static boolean bypass;

    public static boolean isSupported(String fileName) {
        String ext = extension(fileName);
        return ext.equals("pdf") || ext.equals("docx") || ext.equals("xlsx") || ext.equals("xls");
    }

    static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1 ? fileName.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    /** Returns true when the file was opened in the in-app viewer. */
    public static boolean tryOpen(Activity activity, File file, String fileName, boolean restrict, boolean protectedContent) {
        try {
            if (bypass || restrict || activity == null || file == null || !file.exists()) {
                return false;
            }
            if (!ParadoksConfig.docViewerEnabled()) {
                return false;
            }
            String name = fileName != null && !fileName.isEmpty() ? fileName : file.getName();
            if (!isSupported(name) || file.length() > MAX_SIZE || file.length() == 0) {
                return false;
            }
            BaseFragment last = LaunchActivity.getLastFragment();
            if (last == null) {
                return false;
            }
            if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
                PhotoViewer.getInstance().closePhoto(false, false);
            }
            last.presentFragment(new ParadoksDocViewerActivity(file, name, protectedContent));
            if (!protectedContent && ParadoksConfig.docAutoSave()) {
                saveToDownloads(activity, file, name, true);
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** "Open with another app" from inside the viewer: runs Telegram's normal flow once. */
    static void openExternally(Activity activity, File file, String name) {
        bypass = true;
        try {
            AndroidUtilities.openForView(file, name, null, activity, null, false);
        } finally {
            bypass = false;
        }
    }

    // ------------------------------------------------------------------ Downloads

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String keyOf(String name, File file) {
        return name + "|" + file.length();
    }

    private static boolean wasSaved(String key) {
        return prefs().getStringSet(KEY_SAVED, new HashSet<>()).contains(key);
    }

    private static void markSaved(String key) {
        Set<String> set = new HashSet<>(prefs().getStringSet(KEY_SAVED, new HashSet<>()));
        if (set.size() > 500) {
            set.clear(); // only a duplicate guard: forgetting old entries is harmless
        }
        set.add(key);
        prefs().edit().putStringSet(KEY_SAVED, set).apply();
    }

    static String mimeOf(String name) {
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(name));
        return mime != null ? mime : "application/octet-stream";
    }

    private static String safeName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (cleaned.length() > 120) {
            String ext = extension(cleaned);
            cleaned = cleaned.substring(0, 110) + (ext.isEmpty() ? "" : "." + ext);
        }
        return cleaned.isEmpty() ? "fayl" : cleaned;
    }

    /**
     * Copies the document to Downloads/Telegram under its real name (Telegram's cache names are numeric).
     * With auto = true a document that was already saved once is not saved again.
     */
    static void saveToDownloads(Context context, File source, String name, boolean auto) {
        final String key = keyOf(name, source);
        if (auto && wasSaved(key)) {
            return;
        }
        final Context appContext = ApplicationLoader.applicationContext;
        new Thread(() -> {
            File tmp = null;
            try {
                File base = appContext.getExternalFilesDir(null);
                if (base == null) {
                    base = appContext.getFilesDir();
                }
                File dir = new File(base, "paradoks_docs_tmp");
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        if (System.currentTimeMillis() - f.lastModified() > 3600_000L) {
                            f.delete();
                        }
                    }
                }
                tmp = new File(dir, safeName(name));
                try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                final File copy = tmp;
                AndroidUtilities.runOnUIThread(() -> MediaController.saveFile(copy.getAbsolutePath(), context, 2, copy.getName(), mimeOf(name), uri -> {
                    markSaved(key);
                    copy.delete();
                    Toast.makeText(appContext, "Yuklashlar/Telegram papkasiga saqlandi: " + copy.getName(), Toast.LENGTH_SHORT).show();
                }, false));
            } catch (Throwable e) {
                if (tmp != null) {
                    tmp.delete();
                }
                if (!auto) {
                    AndroidUtilities.runOnUIThread(() -> Toast.makeText(appContext, "Saqlab bo'lmadi", Toast.LENGTH_SHORT).show());
                }
            }
        }, "ParadoksDocSave").start();
    }

    private ParadoksDocViewer() {
    }
}
