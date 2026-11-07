package uz.paradoks.gram;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.view.Menu;
import android.widget.EditText;
import android.widget.Toast;

import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.AlertDialog;

/**
 * Uzbek Latin &lt;-&gt; Cyrillic converter (rule based, no network).
 * The direction is picked from the text itself: mostly Cyrillic letters become Latin, otherwise Latin becomes Cyrillic.
 * Words that look like links, @usernames, #hashtags or commands are left untouched.
 */
public final class ParadoksTranslit {

    /** Menu item id used in the input field selection menu. */
    public static final int MENU_ID = 0x70A5D001;

    private ParadoksTranslit() {
    }

    // ------------------------------------------------------------------ public API

    public static boolean isCyrillic(char c) {
        return (c >= 'Ѐ' && c <= 'ӿ');
    }

    private static boolean isLatinLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** True when the text has letters this converter can work on. */
    public static boolean hasConvertibleLetters(CharSequence text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isLatinLetter(c) || isCyrillic(c)) {
                return true;
            }
        }
        return false;
    }

    /** Converts to the other script, deciding the direction by counting letters. */
    public static String convert(String text) {
        return isMostlyCyrillic(text) ? toLatin(text) : toCyrillic(text);
    }

    public static boolean isMostlyCyrillic(String text) {
        int cyr = 0;
        int lat = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCyrillic(c)) {
                cyr++;
            } else if (isLatinLetter(c)) {
                lat++;
            }
        }
        return cyr > lat;
    }

    public static String directionLabel(String text) {
        return isMostlyCyrillic(text) ? "Kirill → Lotin" : "Lotin → Kirill";
    }

    // ------------------------------------------------------------------ word handling

    private static boolean isSkippedToken(String token) {
        return token.startsWith("@") || token.startsWith("#") || token.startsWith("/") || token.contains("://")
                || token.startsWith("www.") || token.contains("@");
    }

    private interface WordConverter {
        String convert(String word);
    }

    /** Applies the converter to each whitespace separated token, keeping the whitespace and skipping links and mentions. */
    private static String eachToken(String text, WordConverter converter) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (Character.isWhitespace(text.charAt(i))) {
                out.append(text.charAt(i));
                i++;
                continue;
            }
            int j = i;
            while (j < n && !Character.isWhitespace(text.charAt(j))) {
                j++;
            }
            String token = text.substring(i, j);
            out.append(isSkippedToken(token) ? token : converter.convert(token));
            i = j;
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ Latin -> Cyrillic

    public static String toCyrillic(String text) {
        return eachToken(text, ParadoksTranslit::latinWordToCyrillic);
    }

    private static boolean isApostrophe(char c) {
        return c == '\'' || c == '`' || c == 'ʻ' || c == 'ʼ' || c == '‘' || c == '’' || c == '´' || c == 'ʹ';
    }

    private static boolean isUpper(char c) {
        return Character.isUpperCase(c);
    }

    private static char lower(char c) {
        return Character.toLowerCase(c);
    }

    private static boolean isLatinVowel(char c) {
        c = lower(c);
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    /** Cases a (possibly multi letter) Cyrillic result like the Latin source letters: first upper, all upper if the next letter is upper too. */
    private static String cased(String cyr, boolean firstUpper, boolean allUpper) {
        if (!firstUpper) {
            return cyr;
        }
        return allUpper ? cyr.toUpperCase() : Character.toUpperCase(cyr.charAt(0)) + cyr.substring(1);
    }

    private static String latinWordToCyrillic(String w) {
        StringBuilder out = new StringBuilder(w.length());
        int n = w.length();
        int i = 0;
        while (i < n) {
            char c = w.charAt(i);
            char lc = lower(c);
            boolean up = isUpper(c);
            char next = i + 1 < n ? w.charAt(i + 1) : 0;
            char lnext = lower(next);
            boolean nextUp = next != 0 && isUpper(next);
            char next2 = i + 2 < n ? w.charAt(i + 2) : 0;
            boolean wordStart = i == 0 || !isLatinLetter(w.charAt(i - 1)) && !isApostrophe(w.charAt(i - 1));

            if (!isLatinLetter(c)) {
                if (isApostrophe(c)) {
                    // a lone apostrophe between letters is the "tutuq belgisi" (a'lo, ma'no)
                    boolean letterBefore = i > 0 && isLatinLetter(w.charAt(i - 1));
                    boolean letterAfter = next != 0 && isLatinLetter(next);
                    out.append(letterBefore && letterAfter ? "ъ" : String.valueOf(c));
                } else {
                    out.append(c);
                }
                i++;
                continue;
            }

            // o' and g' (o‘, oʻ, g’ ...)
            if ((lc == 'o' || lc == 'g') && next != 0 && isApostrophe(next)) {
                String cyr = lc == 'o' ? "ў" : "ғ";
                out.append(up ? cyr.toUpperCase() : cyr);
                i += 2;
                continue;
            }
            // yo' -> йў (yo'l), not ё
            if (lc == 'y' && lnext == 'o' && next2 != 0 && isApostrophe(next2)) {
                out.append(up ? "Й" : "й");
                i++;
                continue;
            }
            // digraphs
            if (lc == 's' && lnext == 'h') {
                out.append(cased("ш", up, up && nextUp));
                i += 2;
                continue;
            }
            if (lc == 'c' && lnext == 'h') {
                out.append(cased("ч", up, up && nextUp));
                i += 2;
                continue;
            }
            if (lc == 'y' && (lnext == 'o' || lnext == 'u' || lnext == 'a' || lnext == 'e')) {
                String cyr = lnext == 'o' ? "ё" : lnext == 'u' ? "ю" : lnext == 'a' ? "я" : "е";
                out.append(up ? cyr.toUpperCase() : cyr);
                i += 2;
                continue;
            }
            if (lc == 'e') {
                // э at the start of a word and after a vowel (аэропорт, поэзия), е elsewhere
                boolean afterVowel = i > 0 && isLatinVowel(w.charAt(i - 1));
                String cyr = (wordStart || afterVowel) ? "э" : "е";
                out.append(up ? cyr.toUpperCase() : cyr);
                i++;
                continue;
            }
            String single = latinLetter(lc);
            out.append(up ? single.toUpperCase() : single);
            i++;
        }
        return out.toString();
    }

    private static String latinLetter(char lc) {
        switch (lc) {
            case 'a': return "а";
            case 'b': return "б";
            case 'c': return "ц";
            case 'd': return "д";
            case 'f': return "ф";
            case 'g': return "г";
            case 'h': return "ҳ";
            case 'i': return "и";
            case 'j': return "ж";
            case 'k': return "к";
            case 'l': return "л";
            case 'm': return "м";
            case 'n': return "н";
            case 'o': return "о";
            case 'p': return "п";
            case 'q': return "қ";
            case 'r': return "р";
            case 's': return "с";
            case 't': return "т";
            case 'u': return "у";
            case 'v': return "в";
            case 'w': return "в";
            case 'x': return "х";
            case 'y': return "й";
            case 'z': return "з";
            default: return String.valueOf(lc);
        }
    }

    // ------------------------------------------------------------------ Cyrillic -> Latin

    public static String toLatin(String text) {
        return eachToken(text, ParadoksTranslit::cyrillicWordToLatin);
    }

    private static boolean isCyrVowel(char c) {
        switch (Character.toLowerCase(c)) {
            case 'а': // а
            case 'е': // е
            case 'ё': // ё
            case 'и': // и
            case 'о': // о
            case 'у': // у
            case 'ў': // ў
            case 'ы': // ы
            case 'э': // э
            case 'ю': // ю
            case 'я': // я
                return true;
            default:
                return false;
        }
    }

    private static String cyrillicWordToLatin(String w) {
        StringBuilder out = new StringBuilder(w.length() + 4);
        int n = w.length();
        for (int i = 0; i < n; i++) {
            char c = w.charAt(i);
            if (!isCyrillic(c)) {
                out.append(c);
                continue;
            }
            char lc = Character.toLowerCase(c);
            boolean up = Character.isUpperCase(c);
            char next = i + 1 < n ? w.charAt(i + 1) : 0;
            boolean nextUp = next != 0 && isCyrillic(next) && Character.isUpperCase(next);
            char prev = i > 0 ? w.charAt(i - 1) : 0;
            String lat;
            switch (lc) {
                case 'а': lat = "a"; break;
                case 'б': lat = "b"; break;
                case 'в': lat = "v"; break;
                case 'г': lat = "g"; break;
                case 'д': lat = "d"; break;
                case 'е': // е: ye at word start or after a vowel / ъ / ь, otherwise e
                    lat = (i == 0 || !isCyrillic(prev) || isCyrVowel(prev) || prev == 'ъ' || prev == 'ь') ? "ye" : "e";
                    break;
                case 'ё': lat = "yo"; break;
                case 'ж': lat = "j"; break;
                case 'з': lat = "z"; break;
                case 'и': lat = "i"; break;
                case 'й': lat = "y"; break;
                case 'к': lat = "k"; break;
                case 'л': lat = "l"; break;
                case 'м': lat = "m"; break;
                case 'н': lat = "n"; break;
                case 'о': lat = "o"; break;
                case 'п': lat = "p"; break;
                case 'р': lat = "r"; break;
                case 'с': lat = "s"; break;
                case 'т': lat = "t"; break;
                case 'у': lat = "u"; break;
                case 'ф': lat = "f"; break;
                case 'х': lat = "x"; break;
                case 'ц': lat = "ts"; break;
                case 'ч': lat = "ch"; break;
                case 'ш': lat = "sh"; break;
                case 'щ': lat = "shch"; break;
                case 'ъ': out.append('ʼ'); continue; // ъ -> tutuq belgisi
                case 'ы': lat = "i"; break;
                case 'ь': continue; // ь is dropped
                case 'э': lat = "e"; break;
                case 'ю': lat = "yu"; break;
                case 'я': lat = "ya"; break;
                case 'ў': lat = "oʻ"; break; // ў
                case 'қ': lat = "q"; break;
                case 'ғ': lat = "gʻ"; break; // ғ
                case 'ҳ': lat = "h"; break;
                default:
                    out.append(c);
                    continue;
            }
            if (up) {
                if (lat.length() == 1 || nextUp) {
                    out.append(lat.toUpperCase());
                } else {
                    out.append(Character.toUpperCase(lat.charAt(0))).append(lat.substring(1));
                }
            } else {
                out.append(lat);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ UI: message menu

    /** Shows the converted text of a message with a "copy" button. */
    public static void showForMessage(Activity activity, MessageObject message) {
        if (activity == null || message == null || message.messageOwner == null) {
            return;
        }
        final String source = message.messageOwner.message;
        if (source == null || !hasConvertibleLetters(source)) {
            Toast.makeText(activity, "Bu xabarda o'giriladigan matn yo'q", Toast.LENGTH_SHORT).show();
            return;
        }
        final String converted = convert(source);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(directionLabel(source));
        builder.setMessage(converted);
        builder.setPositiveButton("Nusxalash", (d, w) -> {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("translit", converted));
                Toast.makeText(activity, "Nusxa olindi", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Yopish", null);
        builder.show();
    }

    // ------------------------------------------------------------------ UI: input field selection menu

    /** Adds "Lotin ↔ Kirill" to the text selection menu of the message input field. */
    public static void addMenuItem(Menu menu) {
        if (menu == null || !ParadoksConfig.translitEnabled()) {
            return;
        }
        if (menu.findItem(MENU_ID) == null) {
            menu.add(0, MENU_ID, 0, "Lotin ↔ Kirill");
        }
    }

    /** Replaces the selected text of the input field with its converted form. */
    public static void convertSelection(EditText field) {
        Editable text = field.getText();
        if (text == null) {
            return;
        }
        int start = Math.min(field.getSelectionStart(), field.getSelectionEnd());
        int end = Math.max(field.getSelectionStart(), field.getSelectionEnd());
        if (start < 0 || end <= start) {
            return;
        }
        String selected = text.subSequence(start, end).toString();
        if (!hasConvertibleLetters(selected)) {
            return;
        }
        String converted = convert(selected);
        text.replace(start, end, converted);
        try {
            field.setSelection(start, start + converted.length());
        } catch (Throwable ignore) {
        }
    }
}
