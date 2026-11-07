package uz.paradoks.gram;

import android.content.Intent;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;
import java.util.Locale;

/** "Metadata (EXIF) ni ko'rish": camera, date, software and GPS of an image file. */
public final class ParadoksExifViewer {

    private static final String[][] TAGS = {
            {"Make", "Ishlab chiqaruvchi"},
            {"Model", "Qurilma modeli"},
            {"LensModel", "Obyektiv"},
            {"Software", "Dastur"},
            {"DateTimeOriginal", "Suratga olingan sana"},
            {"DateTimeDigitized", "Raqamlashtirilgan sana"},
            {"DateTime", "O'zgartirilgan sana"},
            {"OffsetTimeOriginal", "Vaqt zonasi"},
            {"ExposureTime", "Ekspozitsiya (s)"},
            {"FNumber", "Diafragma (f)"},
            {"ISOSpeedRatings", "ISO"},
            {"FocalLength", "Fokus masofasi (mm)"},
            {"Flash", "Chaqmoq"},
            {"WhiteBalance", "Oq balans"},
            {"Orientation", "Yo'nalish"},
            {"ImageWidth", "Kengligi"},
            {"ImageLength", "Balandligi"},
            {"Artist", "Muallif"},
            {"Copyright", "Mualliflik huquqi"},
            {"GPSAltitude", "Balandlik (GPS)"},
    };

    public static void show(BaseFragment fragment, String path, String name) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        File file = path != null ? new File(path) : null;
        if (file == null || !file.exists() || file.length() == 0) {
            AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(fragment.getParentActivity(),
                    "Avval rasmni yuklab oling", android.widget.Toast.LENGTH_SHORT).show());
            return;
        }
        final StringBuilder text = new StringBuilder();
        if (name != null && !name.isEmpty()) {
            text.append("Fayl: ").append(name).append('\n');
        }
        text.append("Hajmi: ").append(AndroidUtilities.formatFileSize(file.length())).append('\n');
        double[] latLong = null;
        int found = 0;
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            for (String[] tag : TAGS) {
                String value = exif.getAttribute(tag[0]);
                if (value != null && !value.trim().isEmpty() && !"0".equals(value.trim()) || value != null && tag[0].equals("Flash")) {
                    text.append(tag[1]).append(": ").append(value.trim()).append('\n');
                    found++;
                }
            }
            latLong = exif.getLatLong();
        } catch (Throwable e) {
            text.append("\nMetadatani o'qib bo'lmadi.");
        }
        if (latLong != null) {
            text.append(String.format(Locale.US, "GPS joylashuv: %.6f, %.6f", latLong[0], latLong[1])).append('\n');
            found++;
        }
        if (found == 0) {
            text.append("\nBu rasmda metadata yo'q. Telegram oddiy rasm sifatida yuborilganda metadatani o'chiradi, "
                    + "u faqat fayl sifatida yuborilgan rasmlarda saqlanadi.");
        }
        final String result = text.toString().trim();
        final double[] geo = latLong;
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle("Metadata (EXIF)");
        builder.setMessage(result);
        builder.setPositiveButton(LocaleController.getString(R.string.Close), null);
        builder.setNeutralButton("Nusxalash", (d, w) -> {
            AndroidUtilities.addToClipboard(result);
            android.widget.Toast.makeText(fragment.getParentActivity(), "Nusxalandi", android.widget.Toast.LENGTH_SHORT).show();
        });
        if (geo != null) {
            builder.setNegativeButton("Xaritada ochish", (d, w) -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f", geo[0], geo[1], geo[0], geo[1])));
                    fragment.getParentActivity().startActivity(intent);
                } catch (Throwable e) {
                    android.widget.Toast.makeText(fragment.getParentActivity(), "Xarita ilovasi topilmadi", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }
        fragment.showDialog(builder.create());
    }

    private ParadoksExifViewer() {
    }
}
