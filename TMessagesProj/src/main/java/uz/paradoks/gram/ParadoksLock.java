package uz.paradoks.gram;

import android.content.Context;
import android.widget.Toast;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

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

    private static void toast(BaseFragment fragment, String text) {
        if (fragment.getParentActivity() != null) {
            Toast.makeText(fragment.getParentActivity(), text, Toast.LENGTH_SHORT).show();
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

    private static void promptUnlock(BaseFragment fragment, int account, long dialogId, String error) {
        ParadoksPinScreen.unlock(fragment.getParentActivity(), "Chat qulflangan", "PIN kodni kiriting yoki barmoq izidan foydalaning", true,
                ParadoksLock::check, pin -> {
                    prompting = false;
                    UNLOCKED.add(key(account, dialogId));
                    if (fragment.getFragmentView() != null) {
                        fragment.getFragmentView().setVisibility(android.view.View.VISIBLE);
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
            ParadoksPinScreen.unlock(fragment.getParentActivity(), "Qulfni olib tashlash", "PIN kodni kiriting", true, ParadoksLock::check, pin -> {
                chats.remove(k);
                ParadoksConfig.setLockedChats(chats);
                UNLOCKED.remove(k);
                toast(fragment, "Chat qulfi olib tashlandi");
            }, null);
        } else {
            chats.add(k);
            ParadoksConfig.setLockedChats(chats);
            UNLOCKED.add(k);
            toast(fragment, "Chat qulflandi. Keyingi safar ochganda PIN so'raladi");
        }
    }

    private static void setNewPin(BaseFragment fragment, Runnable onDone) {
        ParadoksPinScreen.create(fragment.getParentActivity(), "Yangi PIN", "Chat qulfi uchun PIN o'rnating (kamida 4 raqam)", pin -> {
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
        ParadoksPinScreen.unlock(fragment.getParentActivity(), title, "PIN kodni kiriting yoki barmoq izidan foydalaning", true,
                ParadoksLock::check, pin -> onSuccess.run(), null);
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
        final boolean canBiometric = ParadoksPinScreen.biometricAvailable(context);
        java.util.ArrayList<CharSequence> items = new java.util.ArrayList<>();
        items.add("PIN ni o'zgartirish");
        items.add("PIN ni o'chirish (hamma qulflar olinadi)");
        if (canBiometric) {
            items.add("Barmoq izi / yuz bilan ochish: " + (ParadoksConfig.lockBiometric() ? "yoqilgan" : "o'chirilgan"));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Chat qulfi PIN");
        builder.setItems(items.toArray(new CharSequence[0]), (d, which) -> {
            if (which == 2) {
                ParadoksConfig.setLockBiometric(!ParadoksConfig.lockBiometric());
                toast(fragment, ParadoksConfig.lockBiometric() ? "Barmoq izi / yuz bilan ochish yoqildi" : "Barmoq izi / yuz bilan ochish o'chirildi");
                if (onChanged != null) {
                    onChanged.run();
                }
                return;
            }
            ParadoksPinScreen.unlock(fragment.getParentActivity(), "Joriy PIN", "Davom etish uchun joriy PIN ni kiriting", true, ParadoksLock::check, pin -> {
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
