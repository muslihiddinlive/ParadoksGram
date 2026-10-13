package uz.paradoks.gram;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;

/** Small helpers that explain failures which Telegram's code swallows silently. */
public final class ParadoksDiag {

    /**
     * Text for the passkey login error dialog. It shows the real error plus the package name and
     * the signing certificate fingerprint: Android passkeys only work when the domain that owns the
     * passkey (telegram.org) lists exactly this package and certificate.
     */
    public static String passkeyReport(Context context, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("Xato: ").append(error == null || error.isEmpty() ? "noma'lum" : error).append("\n\n");
        sb.append("Paket: ").append(context.getPackageName()).append('\n');
        sb.append("Imzo SHA-256:\n").append(signingSha256(context)).append("\n\n");
        sb.append("Passkey Telegram'ning telegram.org domeniga bog'langan. Android shu domen e'lon qilgan ro'yxatda "
                + "ilovaning paketi va imzosi bo'lishini talab qiladi. Boshqa paket va imzoli ilova uchun bu ro'yxatda "
                + "yozuv bo'lmasa, passkey ishlamaydi. Telefon raqam yoki boshqa usul bilan kiring.");
        return sb.toString();
    }

    private static String signingSha256(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) {
                return "topilmadi";
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) {
                    hex.append(':');
                }
                hex.append(String.format("%02X", digest[i]));
            }
            return hex.toString();
        } catch (Throwable e) {
            return "aniqlab bo'lmadi";
        }
    }

    private ParadoksDiag() {
    }
}
