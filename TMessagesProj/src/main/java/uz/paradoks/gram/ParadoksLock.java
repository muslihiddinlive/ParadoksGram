package uz.paradoks.gram;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-chat PIN lock. Locked chats ask for the PIN every time they are opened.
 * The PIN is never stored: only a salted SHA-256 hash.
 */
public final class ParadoksLock {

    /** Chats unlocked while they stay open (cleared when the chat screen is destroyed). */
    private static final Set<String> UNLOCKED = new HashSet<>();
    private static boolean prompting;

    private static String key(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    public static boolean hasPin() {
        return ParadoksConfig.lockHash() != null;
    }

    public static boolean isLocked(int account, long dialogId) {
        if (!hasPin()) {
            return false;
        }
        if (ParadoksConfig.lockedChats().contains(key(account, dialogId))) {
            return true;
        }
        // A hidden chat opened outside the hidden list (a link, for example) needs the PIN too.
        return !ParadoksHidden.isShowing() && ParadoksHidden.isHidden(account, dialogId);
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest((salt + ":" + pin).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean check(String pin) {
        String salt = ParadoksConfig.lockSalt();
        String stored = ParadoksConfig.lockHash();
        return salt != null && stored != null && stored.equals(hash(salt, pin));
    }

    private static void savePin(String pin) {
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        String salt = sb.toString();
        ParadoksConfig.setLockPin(salt, hash(salt, pin));
    }

    private interface PinCallback {
        void onPin(String pin);
    }

    private static void askPin(BaseFragment fragment, String title, String message, boolean cancelable, PinCallback ok, Runnable cancel) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            if (cancel != null) {
                cancel.run();
            }
            return;
        }
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("PIN (4+ raqam)");
        input.setSingleLine(true);
        input.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12), AndroidUtilities.dp(24), AndroidUtilities.dp(12));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setView(input);
        final boolean[] done = new boolean[1];
        builder.setPositiveButton("OK", (d, w) -> {
            done[0] = true;
            ok.onPin(input.getText().toString().trim());
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
            done[0] = true;
            if (cancel != null) {
                cancel.run();
            }
        });
        AlertDialog dialog = builder.create();
        if (!cancelable) {
            dialog.setCanCancel(false);
            dialog.setCanceledOnTouchOutside(false);
        } else {
            dialog.setOnCancelListener(d -> {
                if (!done[0] && cancel != null) {
                    cancel.run();
                }
            });
        }
        fragment.showDialog(dialog);
    }

    private static void toast(BaseFragment fragment, String text) {
        if (fragment.getParentActivity() != null) {
            Toast.makeText(fragment.getParentActivity(), text, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ fingerprint / face

    /** True when this phone can check a fingerprint or face. */
    public static boolean biometricAvailable() {
        try {
            return BiometricManager.from(ApplicationLoader.applicationContext)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fingerprint / face unlock is on, a PIN exists (it stays the fallback) and the phone supports it. */
    private static boolean biometricUsable() {
        return ParadoksConfig.lockBiometric() && hasPin() && biometricAvailable() && LaunchActivity.instance != null;
    }

    /** Shows the system fingerprint prompt. Any failure, cancel or the "PIN" button calls {@code fallback} (the PIN dialog). */
    private static void promptBiometric(String title, Runnable onSuccess, Runnable fallback) {
        final FragmentActivity activity = LaunchActivity.instance;
        if (activity == null) {
            fallback.run();
            return;
        }
        try {
            BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    onSuccess.run();
                }

                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    fallback.run();
                }
            });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setNegativeButtonText("PIN kiritish")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .build();
            prompt.authenticate(info);
        } catch (Throwable t) {
            fallback.run();
        }
    }

    /** Called from ChatActivity.onResume: hides a locked chat and asks for the PIN. */
    public static void onChatResume(BaseFragment fragment, int account, long dialogId) {
        if (!isLocked(account, dialogId) || UNLOCKED.contains(key(account, dialogId))) {
            return;
        }
        if (fragment.getFragmentView() != null) {
            fragment.getFragmentView().setVisibility(android.view.View.INVISIBLE);
        }
        if (prompting) {
            return;
        }
        prompting = true;
        promptUnlock(fragment, account, dialogId, "");
    }

    private static void unlockChat(BaseFragment fragment, int account, long dialogId) {
        prompting = false;
        UNLOCKED.add(key(account, dialogId));
        if (fragment.getFragmentView() != null) {
            fragment.getFragmentView().setVisibility(android.view.View.VISIBLE);
        }
    }

    private static void promptUnlock(BaseFragment fragment, int account, long dialogId, String error) {
        if (error.isEmpty() && biometricUsable()) {
            promptBiometric("Chat qulflangan", () -> unlockChat(fragment, account, dialogId),
                    () -> promptUnlockPin(fragment, account, dialogId, ""));
            return;
        }
        promptUnlockPin(fragment, account, dialogId, error);
    }

    private static void promptUnlockPin(BaseFragment fragment, int account, long dialogId, String error) {
        askPin(fragment, "Chat qulflangan", error.isEmpty() ? "PIN kodni kiriting" : error, false, pin -> {
            if (check(pin)) {
                unlockChat(fragment, account, dialogId);
            } else {
                promptUnlock(fragment, account, dialogId, "PIN noto'g'ri, qayta urinib ko'ring");
            }
        }, () -> {
            prompting = false;
            fragment.finishFragment();
        });
    }

    /** Called from ChatActivity.onFragmentDestroy. */
    public static void onChatClosed(int account, long dialogId) {
        UNLOCKED.remove(key(account, dialogId));
    }

    /** Chat menu item: lock or unlock this chat (sets the PIN first when there is none). */
    public static void toggleChat(BaseFragment fragment, int account, long dialogId) {
        if (!hasPin()) {
            setNewPin(fragment, () -> toggleChat(fragment, account, dialogId));
            return;
        }
        Set<String> chats = ParadoksConfig.lockedChats();
        String k = key(account, dialogId);
        if (chats.contains(k)) {
            askPin(fragment, "Qulfni olib tashlash", "PIN kodni kiriting", true, pin -> {
                if (check(pin)) {
                    chats.remove(k);
                    ParadoksConfig.setLockedChats(chats);
                    UNLOCKED.remove(k);
                    toast(fragment, "Chat qulfi olib tashlandi");
                } else {
                    toast(fragment, "PIN noto'g'ri");
                }
            }, null);
        } else {
            chats.add(k);
            ParadoksConfig.setLockedChats(chats);
            UNLOCKED.add(k);
            toast(fragment, "Chat qulflandi. Keyingi safar ochganda PIN so'raladi");
        }
    }

    private static void setNewPin(BaseFragment fragment, Runnable onDone) {
        askPin(fragment, "Yangi PIN", "Chat qulfi uchun PIN o'rnating (kamida 4 raqam)", true, pin -> {
            if (pin.length() < 4) {
                toast(fragment, "PIN kamida 4 raqam bo'lsin");
                setNewPin(fragment, onDone);
                return;
            }
            savePin(pin);
            toast(fragment, "PIN o'rnatildi");
            if (onDone != null) {
                onDone.run();
            }
        }, null);
    }

    /** Asks for the PIN (sets one first if there is none) and runs onSuccess when it is right. */
    public static void verify(BaseFragment fragment, String title, Runnable onSuccess) {
        if (!hasPin()) {
            setNewPin(fragment, onSuccess);
            return;
        }
        if (biometricUsable()) {
            promptBiometric(title, onSuccess, () -> verifyPin(fragment, title, onSuccess));
            return;
        }
        verifyPin(fragment, title, onSuccess);
    }

    private static void verifyPin(BaseFragment fragment, String title, Runnable onSuccess) {
        askPin(fragment, title, "PIN kodni kiriting", true, pin -> {
            if (check(pin)) {
                onSuccess.run();
            } else {
                toast(fragment, "PIN noto'g'ri");
            }
        }, null);
    }

    /** Settings page: set, change or remove the PIN. */
    public static void manage(BaseFragment fragment, Runnable onChanged) {
        if (!hasPin()) {
            setNewPin(fragment, onChanged);
            return;
        }
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Chat qulfi PIN");
        builder.setItems(new CharSequence[]{"PIN ni o'zgartirish", "PIN ni o'chirish (hamma qulflar olinadi)"}, (d, which) -> {
            askPin(fragment, "Joriy PIN", "Davom etish uchun joriy PIN ni kiriting", true, pin -> {
                if (!check(pin)) {
                    toast(fragment, "PIN noto'g'ri");
                    return;
                }
                if (which == 0) {
                    setNewPin(fragment, onChanged);
                } else {
                    ParadoksConfig.clearLockPin();
                    UNLOCKED.clear();
                    toast(fragment, "PIN va qulflar o'chirildi");
                    if (onChanged != null) {
                        onChanged.run();
                    }
                }
            }, null);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private ParadoksLock() {
    }
}
