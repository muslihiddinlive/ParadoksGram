package uz.paradoks.gram;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * Readers for Office documents that need no external library and no Android classes:
 * .docx (text, headings, lists, tables) and .xlsx (all sheets, with number and date formats).
 * Old .xls files are read by {@link ParadoksXlsReader}, which shares the models and the number formatting.
 *
 * Everything is streamed with SAX, entries are size-limited (zip bombs) and DOCTYPE/external entities are refused.
 * Images, charts, headers/footers, footnotes and text boxes of Word files are not shown.
 */
public final class ParadoksDocParsers {

    public static final int MAX_ROWS = 5000;
    public static final int MAX_COLS = 100;
    static final long MAX_ENTRY_BYTES = 80L * 1024 * 1024;
    static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    private static final int MAX_BLOCKS = 30000;
    private static final int MAX_IMAGES = 300;

    /** A problem with the document that can be shown to the user as is. */
    public static class ParseException extends IOException {
        public ParseException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ models

    public static final class Sheet {
        public String name = "";
        public final ArrayList<String[]> rows = new ArrayList<>();
        public int cols;
        public boolean truncatedRows;
        public boolean truncatedCols;
    }

    public static final class Run {
        public final String text;
        public final boolean bold;
        public final boolean italic;
        public final boolean underline;
        public final boolean strike;

        Run(String text, boolean bold, boolean italic, boolean underline, boolean strike) {
            this.text = text;
            this.bold = bold;
            this.italic = italic;
            this.underline = underline;
            this.strike = strike;
        }
    }

    public static final class Block {
        public static final int PARAGRAPH = 0;
        public static final int TABLE = 1;
        public static final int IMAGE = 2;
        public int type;
        /** Path of the image inside the .docx archive (IMAGE blocks only). */
        public String imagePath;
        /** 0 = normal text, 1..6 = heading level. */
        public int heading;
        /** List marker ("• ", "1. ") already including the indent. */
        public String prefix = "";
        public final ArrayList<Run> runs = new ArrayList<>();
        public final ArrayList<ArrayList<String>> table = new ArrayList<>();
    }

    public static final class Doc {
        public final ArrayList<Block> blocks = new ArrayList<>();
        public boolean truncated;
    }

    // ------------------------------------------------------------------ SAX plumbing

    private static class Base extends DefaultHandler {
        boolean stopped;

        void stop() throws SAXException {
            stopped = true;
            throw new SAXException("stop");
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) {
            return new InputSource(new ByteArrayInputStream(new byte[0]));
        }
    }

    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long limit) {
            super(in);
            left = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && --left < 0) {
                throw new ParseException("Fayl ichidagi ma'lumot juda katta");
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = super.read(buffer, off, len);
            if (n > 0) {
                left -= n;
                if (left < 0) {
                    throw new ParseException("Fayl ichidagi ma'lumot juda katta");
                }
            }
            return n;
        }
    }

    private static InputStream open(ZipFile zip, ZipEntry entry) throws IOException {
        return new Limited(zip.getInputStream(entry), MAX_ENTRY_BYTES);
    }

    private static void feature(SAXParserFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Throwable ignore) {
        }
    }

    private static void sax(InputStream in, Base handler) throws IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            feature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            feature(factory, "http://xml.org/sax/features/external-general-entities", false);
            feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            factory.newSAXParser().parse(in, handler);
        } catch (SAXException e) {
            if (!handler.stopped) {
                throw new ParseException("Fayl ichidagi ma'lumot buzilgan yoki xavfli tuzilishga ega");
            }
        } catch (ParserConfigurationException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static String local(Attributes atts, int i) {
        String name = atts.getLocalName(i);
        if (name == null || name.isEmpty()) {
            name = atts.getQName(i);
            int colon = name.indexOf(':');
            if (colon >= 0) {
                name = name.substring(colon + 1);
            }
        }
        return name;
    }

    /** Attribute by local name, whatever its namespace prefix is. */
    private static String attr(Attributes atts, String name) {
        for (int i = 0; i < atts.getLength(); i++) {
            if (local(atts, i).equals(name)) {
                return atts.getValue(i);
            }
        }
        return null;
    }

    /** The r:embed attribute of a picture. */
    private static String embedId(Attributes atts) {
        for (int i = 0; i < atts.getLength(); i++) {
            if (local(atts, i).equals("embed")) {
                return atts.getValue(i);
            }
        }
        return null;
    }

    /** Resolves a relationship target of word/document.xml to an archive path. */
    static String resolveWordTarget(String target) {
        if (target == null || target.contains("://")) {
            return null; // linked (external) pictures are not loaded
        }
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        String path = "word/" + target;
        while (path.contains("/../")) {
            int i = path.indexOf("/../");
            int prev = path.lastIndexOf('/', i - 1);
            path = (prev >= 0 ? path.substring(0, prev) : "") + path.substring(i + 3);
        }
        return path;
    }

    public static boolean isDecodableImage(String path) {
        String lower = path.toLowerCase(Locale.US);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }

    /** The r:id style relationship attribute (a namespaced attribute whose local name is "id"). */
    private static String relId(Attributes atts) {
        for (int i = 0; i < atts.getLength(); i++) {
            String uri = atts.getURI(i);
            if (local(atts, i).equals("id") && uri != null && uri.contains("relationships")) {
                return atts.getValue(i);
            }
        }
        return attr(atts, "id");
    }

    private static boolean isOff(String value) {
        return value != null && (value.equals("0") || value.equals("false") || value.equals("off") || value.equals("none"));
    }

    private static String name(String localName, String qName) {
        if (localName != null && !localName.isEmpty()) {
            return localName;
        }
        int colon = qName.indexOf(':');
        return colon >= 0 ? qName.substring(colon + 1) : qName;
    }

    // ------------------------------------------------------------------ DOCX

    private static final class StyleInfo {
        String name = "";
        String numId;
        int ilvl;
    }

    private static final class StylesHandler extends Base {
        final HashMap<String, StyleInfo> styles = new HashMap<>();
        private StyleInfo cur;
        private String curId;
        private boolean inNumPr;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            String n = name(localName, qName);
            switch (n) {
                case "style":
                    curId = attr(atts, "styleId");
                    cur = new StyleInfo();
                    break;
                case "name":
                    if (cur != null && attr(atts, "val") != null) {
                        cur.name = attr(atts, "val");
                    }
                    break;
                case "numPr":
                    inNumPr = true;
                    break;
                case "numId":
                    if (cur != null && inNumPr) {
                        cur.numId = attr(atts, "val");
                    }
                    break;
                case "ilvl":
                    if (cur != null && inNumPr) {
                        cur.ilvl = parseInt(attr(atts, "val"), 0);
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String n = name(localName, qName);
            if (n.equals("numPr")) {
                inNumPr = false;
            } else if (n.equals("style")) {
                if (curId != null && cur != null) {
                    styles.put(curId, cur);
                }
                cur = null;
            }
        }
    }

    private static final class NumberingHandler extends Base {
        final HashMap<String, String[]> abstractFormats = new HashMap<>();
        final HashMap<String, String> numToAbstract = new HashMap<>();
        private String[] curAbstract;
        private String curAbstractId;
        private int curLvl;
        private String curNum;
        private boolean inNum;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            String n = name(localName, qName);
            switch (n) {
                case "abstractNum":
                    curAbstractId = attr(atts, "abstractNumId");
                    curAbstract = new String[9];
                    break;
                case "lvl":
                    curLvl = Math.max(0, Math.min(8, parseInt(attr(atts, "ilvl"), 0)));
                    break;
                case "numFmt":
                    if (curAbstract != null) {
                        curAbstract[curLvl] = attr(atts, "val");
                    }
                    break;
                case "num":
                    curNum = attr(atts, "numId");
                    inNum = true;
                    break;
                case "abstractNumId":
                    if (inNum && curNum != null) {
                        numToAbstract.put(curNum, attr(atts, "val"));
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String n = name(localName, qName);
            if (n.equals("abstractNum")) {
                if (curAbstractId != null && curAbstract != null) {
                    abstractFormats.put(curAbstractId, curAbstract);
                }
                curAbstract = null;
            } else if (n.equals("num")) {
                inNum = false;
            }
        }

        String format(String numId, int ilvl) {
            String abstractId = numToAbstract.get(numId);
            String[] formats = abstractId != null ? abstractFormats.get(abstractId) : null;
            String f = formats != null ? formats[Math.max(0, Math.min(8, ilvl))] : null;
            return f != null ? f : "bullet";
        }
    }

    private static final class DocHandler extends Base {
        final Doc doc = new Doc();
        private final HashMap<String, StyleInfo> styles;
        private final NumberingHandler numbering;
        private final HashMap<String, int[]> counters = new HashMap<>();
        private final HashMap<String, String> relationships;
        private final ArrayList<String> paragraphImages = new ArrayList<>();
        private int imageCount;

        private int skipDepth;
        // paragraph state
        private boolean inParagraph;
        private boolean inPPr;
        private boolean inNumPr;
        private boolean inRun;
        private boolean inRPr;
        private boolean collecting;
        private String pStyle;
        private String numId;
        private int ilvl;
        private boolean bold, italic, underline, strike;
        private final StringBuilder text = new StringBuilder();
        private final ArrayList<Run> runs = new ArrayList<>();
        // table state
        private int tblDepth;
        private ArrayList<ArrayList<String>> rows;
        private ArrayList<String> row;
        private StringBuilder cell;

        DocHandler(HashMap<String, StyleInfo> styles, NumberingHandler numbering, HashMap<String, String> relationships) {
            this.styles = styles;
            this.numbering = numbering;
            this.relationships = relationships;
        }

        private void addText(String s) {
            if (s.isEmpty()) {
                return;
            }
            if (!runs.isEmpty()) {
                Run last = runs.get(runs.size() - 1);
                if (last.bold == bold && last.italic == italic && last.underline == underline && last.strike == strike) {
                    runs.set(runs.size() - 1, new Run(last.text + s, bold, italic, underline, strike));
                    return;
                }
            }
            runs.add(new Run(s, bold, italic, underline, strike));
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
            String n = name(localName, qName);
            if (n.equals("txbxContent") || n.equals("Fallback")) {
                skipDepth++;
                return;
            }
            if (skipDepth > 0) {
                return;
            }
            switch (n) {
                case "p":
                    inParagraph = true;
                    inPPr = false;
                    inRun = false;
                    inRPr = false;
                    pStyle = null;
                    numId = null;
                    ilvl = 0;
                    runs.clear();
                    paragraphImages.clear();
                    break;
                case "pPr":
                    inPPr = true;
                    break;
                case "pStyle":
                    if (inPPr) {
                        pStyle = attr(atts, "val");
                    }
                    break;
                case "numPr":
                    if (inPPr) {
                        inNumPr = true;
                    }
                    break;
                case "numId":
                    if (inNumPr) {
                        numId = attr(atts, "val");
                    }
                    break;
                case "ilvl":
                    if (inNumPr) {
                        ilvl = parseInt(attr(atts, "val"), 0);
                    }
                    break;
                case "r":
                    if (inParagraph) {
                        inRun = true;
                        bold = italic = underline = strike = false;
                    }
                    break;
                case "rPr":
                    if (inRun && !inPPr) {
                        inRPr = true;
                    }
                    break;
                case "b":
                    if (inRPr) {
                        bold = !isOff(attr(atts, "val"));
                    }
                    break;
                case "i":
                    if (inRPr) {
                        italic = !isOff(attr(atts, "val"));
                    }
                    break;
                case "u":
                    if (inRPr) {
                        underline = !isOff(attr(atts, "val"));
                    }
                    break;
                case "strike":
                case "dstrike":
                    if (inRPr) {
                        strike = !isOff(attr(atts, "val"));
                    }
                    break;
                case "t":
                    if (inRun) {
                        collecting = true;
                        text.setLength(0);
                    }
                    break;
                case "tab":
                case "ptab":
                    if (inRun && !inPPr) {
                        addText("\t");
                    }
                    break;
                case "br":
                case "cr":
                    if (inRun) {
                        addText("\n");
                    }
                    break;
                case "noBreakHyphen":
                    if (inRun) {
                        addText("-");
                    }
                    break;
                case "blip":
                    if (inParagraph && tblDepth == 0) {
                        String embed = embedId(atts);
                        String target = embed != null ? relationships.get(embed) : null;
                        String path = target != null ? resolveWordTarget(target) : null;
                        if (path != null && isDecodableImage(path) && imageCount < MAX_IMAGES) {
                            paragraphImages.add(path);
                            imageCount++;
                        }
                    }
                    break;
                case "tbl":
                    tblDepth++;
                    if (tblDepth == 1) {
                        rows = new ArrayList<>();
                    }
                    break;
                case "tr":
                    if (tblDepth == 1) {
                        row = new ArrayList<>();
                    }
                    break;
                case "tc":
                    if (tblDepth == 1) {
                        cell = new StringBuilder();
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (collecting && skipDepth == 0) {
                text.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            String n = name(localName, qName);
            if (n.equals("txbxContent") || n.equals("Fallback")) {
                skipDepth = Math.max(0, skipDepth - 1);
                return;
            }
            if (skipDepth > 0) {
                return;
            }
            switch (n) {
                case "t":
                    if (collecting) {
                        collecting = false;
                        addText(text.toString());
                    }
                    break;
                case "rPr":
                    inRPr = false;
                    break;
                case "r":
                    inRun = false;
                    inRPr = false;
                    break;
                case "pPr":
                    inPPr = false;
                    inNumPr = false;
                    break;
                case "numPr":
                    inNumPr = false;
                    break;
                case "p":
                    endParagraph();
                    break;
                case "tc":
                    if (tblDepth == 1 && row != null && cell != null) {
                        row.add(cell.toString().trim());
                        cell = null;
                    } else if (tblDepth > 1 && cell != null) {
                        cell.append("  ");
                    }
                    break;
                case "tr":
                    if (tblDepth == 1 && rows != null && row != null) {
                        rows.add(row);
                        row = null;
                    }
                    break;
                case "tbl":
                    tblDepth = Math.max(0, tblDepth - 1);
                    if (tblDepth == 0 && rows != null) {
                        if (!rows.isEmpty()) {
                            Block block = new Block();
                            block.type = Block.TABLE;
                            block.table.addAll(rows);
                            add(block);
                        }
                        rows = null;
                    }
                    break;
                default:
                    break;
            }
            if (doc.truncated) {
                stop();
            }
        }

        private void add(Block block) {
            if (doc.blocks.size() >= MAX_BLOCKS) {
                doc.truncated = true;
                return;
            }
            doc.blocks.add(block);
        }

        private int headingLevel() {
            String styleName = "";
            StyleInfo info = pStyle != null ? styles.get(pStyle) : null;
            if (info != null && info.name != null) {
                styleName = info.name.toLowerCase(Locale.US);
            } else if (pStyle != null) {
                styleName = pStyle.toLowerCase(Locale.US);
            }
            if (styleName.startsWith("heading")) {
                for (int i = 7; i < styleName.length(); i++) {
                    char c = styleName.charAt(i);
                    if (c >= '1' && c <= '9') {
                        return Math.min(6, c - '0');
                    }
                }
                return 2;
            }
            if (styleName.equals("title")) {
                return 1;
            }
            if (styleName.equals("subtitle")) {
                return 3;
            }
            return 0;
        }

        private String marker() {
            String id = numId;
            int level = ilvl;
            if (id == null && pStyle != null) {
                StyleInfo info = styles.get(pStyle);
                if (info != null && info.numId != null) {
                    id = info.numId;
                    level = info.ilvl;
                }
            }
            if (id == null || id.equals("0")) {
                return "";
            }
            level = Math.max(0, Math.min(8, level));
            StringBuilder indentBuilder = new StringBuilder();
            for (int i = 0; i < level; i++) {
                indentBuilder.append("    ");
            }
            String indent = indentBuilder.toString();
            String format = numbering.format(id, level);
            if (format.equals("bullet")) {
                return indent + "• ";
            }
            if (format.equals("none")) {
                return indent;
            }
            int[] c = counters.get(id);
            if (c == null) {
                c = new int[9];
                counters.put(id, c);
            }
            c[level]++;
            for (int i = level + 1; i < 9; i++) {
                c[i] = 0;
            }
            int value = c[level];
            switch (format) {
                case "lowerLetter":
                    return indent + letters(value, false) + ". ";
                case "upperLetter":
                    return indent + letters(value, true) + ". ";
                case "lowerRoman":
                    return indent + roman(value).toLowerCase(Locale.US) + ". ";
                case "upperRoman":
                    return indent + roman(value) + ". ";
                default:
                    return indent + value + ". ";
            }
        }

        private void endParagraph() {
            if (!inParagraph) {
                return;
            }
            inParagraph = false;
            inRun = false;
            inRPr = false;
            String marker = marker();
            if (tblDepth > 0) {
                if (cell != null) {
                    StringBuilder plain = new StringBuilder();
                    for (Run run : runs) {
                        plain.append(run.text);
                    }
                    if (plain.length() > 0 || !marker.isEmpty()) {
                        if (cell.length() > 0) {
                            cell.append('\n');
                        }
                        cell.append(marker).append(plain);
                    }
                }
                return;
            }
            if (runs.isEmpty() && !paragraphImages.isEmpty()) {
                addImages();
                return;
            }
            if (runs.isEmpty()) {
                // keep one blank line between blocks, never several in a row
                if (!doc.blocks.isEmpty()) {
                    Block last = doc.blocks.get(doc.blocks.size() - 1);
                    boolean lastBlank = last.type == Block.PARAGRAPH && last.runs.isEmpty() && last.prefix.isEmpty();
                    if (!lastBlank) {
                        add(new Block());
                    }
                }
                return;
            }
            Block block = new Block();
            block.type = Block.PARAGRAPH;
            block.heading = headingLevel();
            block.prefix = marker;
            block.runs.addAll(runs);
            add(block);
            addImages();
        }

        private void addImages() {
            for (String path : paragraphImages) {
                Block image = new Block();
                image.type = Block.IMAGE;
                image.imagePath = path;
                add(image);
            }
            paragraphImages.clear();
        }
    }

    public static Doc readDocx(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry main = zip.getEntry("word/document.xml");
            if (main == null) {
                throw new ParseException("Bu Word (.docx) fayli emas yoki buzilgan");
            }
            StylesHandler styles = new StylesHandler();
            ZipEntry se = zip.getEntry("word/styles.xml");
            if (se != null) {
                try (InputStream in = open(zip, se)) {
                    sax(in, styles);
                } catch (IOException ignore) {
                    // styles only improve headings and lists
                }
            }
            NumberingHandler numbering = new NumberingHandler();
            ZipEntry ne = zip.getEntry("word/numbering.xml");
            if (ne != null) {
                try (InputStream in = open(zip, ne)) {
                    sax(in, numbering);
                } catch (IOException ignore) {
                }
            }
            RelsHandler rels = new RelsHandler();
            ZipEntry re = zip.getEntry("word/_rels/document.xml.rels");
            if (re != null) {
                try (InputStream in = open(zip, re)) {
                    sax(in, rels);
                } catch (IOException ignore) {
                    // without relationships the text is still shown, only pictures are missing
                }
            }
            DocHandler handler = new DocHandler(styles.styles, numbering, rels.targets);
            try (InputStream in = open(zip, main)) {
                sax(in, handler);
            }
            return handler.doc;
        } catch (ZipException e) {
            throw new ParseException("Fayl buzilgan yoki parol bilan himoyalangan");
        }
    }

    // ------------------------------------------------------------------ XLSX

    private static final class SheetRef {
        String name;
        String rid;
        boolean hidden;
    }

    private static final class WorkbookHandler extends Base {
        final ArrayList<SheetRef> sheets = new ArrayList<>();
        boolean date1904;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            String n = name(localName, qName);
            if (n.equals("sheet")) {
                SheetRef ref = new SheetRef();
                ref.name = attr(atts, "name");
                ref.rid = relId(atts);
                String state = attr(atts, "state");
                ref.hidden = state != null && !state.equals("visible");
                sheets.add(ref);
            } else if (n.equals("workbookPr")) {
                String v = attr(atts, "date1904");
                date1904 = v != null && (v.equals("1") || v.equals("true"));
            }
        }
    }

    private static final class RelsHandler extends Base {
        final HashMap<String, String> targets = new HashMap<>();

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            if (name(localName, qName).equals("Relationship")) {
                String id = attr(atts, "Id");
                String target = attr(atts, "Target");
                if (id != null && target != null) {
                    targets.put(id, target);
                }
            }
        }
    }

    private static final class SharedStringsHandler extends Base {
        final ArrayList<String> strings = new ArrayList<>();
        private StringBuilder cur;
        private boolean collecting;
        private int phonetic;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            String n = name(localName, qName);
            if (n.equals("si")) {
                cur = new StringBuilder();
            } else if (n.equals("rPh")) {
                phonetic++;
            } else if (n.equals("t") && cur != null && phonetic == 0) {
                collecting = true;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (collecting) {
                cur.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String n = name(localName, qName);
            if (n.equals("t")) {
                collecting = false;
            } else if (n.equals("rPh")) {
                phonetic = Math.max(0, phonetic - 1);
            } else if (n.equals("si")) {
                strings.add(cur != null ? cur.toString() : "");
                cur = null;
            }
        }
    }

    private static final class StylesFormatHandler extends Base {
        final HashMap<Integer, String> custom = new HashMap<>();
        final ArrayList<Integer> xfFormats = new ArrayList<>();
        private boolean inCellXfs;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            String n = name(localName, qName);
            if (n.equals("numFmt")) {
                int id = parseInt(attr(atts, "numFmtId"), -1);
                String code = attr(atts, "formatCode");
                if (id >= 0 && code != null) {
                    custom.put(id, code);
                }
            } else if (n.equals("cellXfs")) {
                inCellXfs = true;
            } else if (n.equals("xf") && inCellXfs) {
                xfFormats.add(parseInt(attr(atts, "numFmtId"), 0));
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (name(localName, qName).equals("cellXfs")) {
                inCellXfs = false;
            }
        }

        NumFmt[] build() {
            HashMap<Integer, NumFmt> cache = new HashMap<>();
            NumFmt[] result = new NumFmt[xfFormats.size()];
            for (int i = 0; i < result.length; i++) {
                int id = xfFormats.get(i);
                NumFmt f = cache.get(id);
                if (f == null) {
                    f = NumFmt.of(id, custom.get(id));
                    cache.put(id, f);
                }
                result[i] = f;
            }
            return result;
        }
    }

    private static final class SheetHandler extends Base {
        private final Sheet sheet;
        private final ArrayList<String> shared;
        private final NumFmt[] formats;
        private final boolean date1904;

        private int rowNum;
        private ArrayList<String> cells;
        private int colIndex;
        private int nextCol;
        private String type;
        private int style;
        private final StringBuilder value = new StringBuilder();
        private final StringBuilder inline = new StringBuilder();
        private boolean inV;
        private boolean inT;
        private boolean inIs;
        private int phonetic;

        SheetHandler(Sheet sheet, ArrayList<String> shared, NumFmt[] formats, boolean date1904) {
            this.sheet = sheet;
            this.shared = shared;
            this.formats = formats;
            this.date1904 = date1904;
        }

        private static int columnOf(String ref) {
            int col = 0;
            int i = 0;
            while (i < ref.length()) {
                char c = ref.charAt(i);
                if (c >= 'A' && c <= 'Z') {
                    col = col * 26 + (c - 'A' + 1);
                } else if (c >= 'a' && c <= 'z') {
                    col = col * 26 + (c - 'a' + 1);
                } else {
                    break;
                }
                i++;
            }
            return col - 1;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
            String n = name(localName, qName);
            switch (n) {
                case "row": {
                    int r = parseInt(attr(atts, "r"), rowNum + 1);
                    if (r > MAX_ROWS) {
                        sheet.truncatedRows = true;
                        stop();
                    }
                    rowNum = Math.max(1, r);
                    cells = new ArrayList<>();
                    nextCol = 0;
                    break;
                }
                case "c": {
                    String ref = attr(atts, "r");
                    colIndex = ref != null ? columnOf(ref) : nextCol;
                    if (colIndex < 0) {
                        colIndex = nextCol;
                    }
                    nextCol = colIndex + 1;
                    type = attr(atts, "t");
                    style = parseInt(attr(atts, "s"), 0);
                    value.setLength(0);
                    inline.setLength(0);
                    break;
                }
                case "v":
                    inV = true;
                    value.setLength(0);
                    break;
                case "is":
                    inIs = true;
                    break;
                case "rPh":
                    phonetic++;
                    break;
                case "t":
                    if (inIs && phonetic == 0) {
                        inT = true;
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inV) {
                value.append(ch, start, length);
            } else if (inT) {
                inline.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String n = name(localName, qName);
            switch (n) {
                case "v":
                    inV = false;
                    break;
                case "t":
                    inT = false;
                    break;
                case "rPh":
                    phonetic = Math.max(0, phonetic - 1);
                    break;
                case "is":
                    inIs = false;
                    break;
                case "c":
                    finishCell();
                    break;
                case "row":
                    finishRow();
                    break;
                default:
                    break;
            }
        }

        private void finishCell() {
            String result = null;
            String raw = value.toString();
            if (type == null || type.equals("n")) {
                if (!raw.isEmpty()) {
                    try {
                        double v = Double.parseDouble(raw.trim());
                        NumFmt f = style >= 0 && style < formats.length ? formats[style] : NumFmt.GENERAL;
                        result = f.format(v, date1904);
                    } catch (NumberFormatException e) {
                        result = raw;
                    }
                }
            } else if (type.equals("s")) {
                int idx = parseInt(raw.trim(), -1);
                if (idx >= 0 && idx < shared.size()) {
                    result = shared.get(idx);
                }
            } else if (type.equals("inlineStr")) {
                result = inline.toString();
            } else if (type.equals("b")) {
                result = raw.trim().equals("1") ? "TRUE" : "FALSE";
            } else {
                result = raw; // str, e, d
            }
            if (result == null || result.isEmpty()) {
                return;
            }
            if (colIndex >= MAX_COLS) {
                sheet.truncatedCols = true;
                return;
            }
            while (cells.size() <= colIndex) {
                cells.add(null);
            }
            cells.set(colIndex, result);
        }

        private void finishRow() {
            if (cells == null) {
                return;
            }
            int last = cells.size() - 1;
            while (last >= 0 && cells.get(last) == null) {
                last--;
            }
            String[] row = new String[last + 1];
            for (int i = 0; i <= last; i++) {
                row[i] = cells.get(i);
            }
            cells = null;
            while (sheet.rows.size() < rowNum - 1) {
                sheet.rows.add(new String[0]);
            }
            if (rowNum - 1 < sheet.rows.size()) {
                sheet.rows.set(rowNum - 1, row);
            } else {
                sheet.rows.add(row);
            }
            sheet.cols = Math.max(sheet.cols, row.length);
        }
    }

    private static String resolveTarget(String target) {
        if (target == null) {
            return null;
        }
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        String path = "xl/" + target;
        while (path.contains("/../")) {
            int i = path.indexOf("/../");
            int prev = path.lastIndexOf('/', i - 1);
            path = (prev >= 0 ? path.substring(0, prev) : "") + path.substring(i + 3);
        }
        return path;
    }

    public static ArrayList<Sheet> readXlsx(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry wbEntry = zip.getEntry("xl/workbook.xml");
            if (wbEntry == null) {
                throw new ParseException("Bu Excel (.xlsx) fayli emas yoki buzilgan");
            }
            WorkbookHandler workbook = new WorkbookHandler();
            try (InputStream in = open(zip, wbEntry)) {
                sax(in, workbook);
            }
            RelsHandler rels = new RelsHandler();
            ZipEntry re = zip.getEntry("xl/_rels/workbook.xml.rels");
            if (re != null) {
                try (InputStream in = open(zip, re)) {
                    sax(in, rels);
                }
            }
            ArrayList<String> shared = new ArrayList<>();
            ZipEntry ss = zip.getEntry("xl/sharedStrings.xml");
            if (ss != null) {
                SharedStringsHandler h = new SharedStringsHandler();
                try (InputStream in = open(zip, ss)) {
                    sax(in, h);
                }
                shared = h.strings;
            }
            NumFmt[] formats = new NumFmt[0];
            ZipEntry st = zip.getEntry("xl/styles.xml");
            if (st != null) {
                StylesFormatHandler h = new StylesFormatHandler();
                try (InputStream in = open(zip, st)) {
                    sax(in, h);
                }
                formats = h.build();
            }

            ArrayList<Sheet> visible = new ArrayList<>();
            ArrayList<Sheet> hidden = new ArrayList<>();
            for (SheetRef ref : workbook.sheets) {
                String path = resolveTarget(rels.targets.get(ref.rid));
                if (path == null || path.contains("chartsheets/")) {
                    continue;
                }
                ZipEntry entry = zip.getEntry(path);
                if (entry == null) {
                    continue;
                }
                Sheet sheet = new Sheet();
                sheet.name = ref.name != null ? ref.name : "Sheet";
                try (InputStream in = open(zip, entry)) {
                    sax(in, new SheetHandler(sheet, shared, formats, workbook.date1904));
                }
                (ref.hidden ? hidden : visible).add(sheet);
            }
            ArrayList<Sheet> result = visible.isEmpty() ? hidden : visible;
            if (result.isEmpty()) {
                throw new ParseException("Jadvallar topilmadi");
            }
            return result;
        } catch (ZipException e) {
            throw new ParseException("Fayl buzilgan yoki parol bilan himoyalangan");
        }
    }

    // ------------------------------------------------------------------ number and date formats

    /** The parts of an Excel number format that matter for showing a value as text. */
    public static final class NumFmt {
        public static final NumFmt GENERAL = new NumFmt();

        boolean date;
        boolean time;
        boolean seconds;
        boolean percent;
        boolean scientific;
        boolean thousands;
        int decimals = -1;
        /** 0 = day.month.year (default), 1 = year-month-day, 2 = month/day/year. */
        int dateOrder;

        private static final String[] BUILTIN = new String[50];

        static {
            BUILTIN[1] = "0";
            BUILTIN[2] = "0.00";
            BUILTIN[3] = "#,##0";
            BUILTIN[4] = "#,##0.00";
            BUILTIN[9] = "0%";
            BUILTIN[10] = "0.00%";
            BUILTIN[11] = "0.00E+00";
            BUILTIN[37] = "#,##0 ;(#,##0)";
            BUILTIN[38] = "#,##0 ;[Red](#,##0)";
            BUILTIN[39] = "#,##0.00;(#,##0.00)";
            BUILTIN[40] = "#,##0.00;[Red](#,##0.00)";
            BUILTIN[41] = "_(* #,##0_);_(* (#,##0);_(* \"-\"_);_(@_)";
            BUILTIN[42] = "_($* #,##0_);_($* (#,##0);_($* \"-\"_);_(@_)";
            BUILTIN[43] = "_(* #,##0.00_);_(* (#,##0.00);_(* \"-\"??_);_(@_)";
            BUILTIN[44] = "_($* #,##0.00_);_($* (#,##0.00);_($* \"-\"??_);_(@_)";
            BUILTIN[48] = "##0.0E+0";
        }

        public static NumFmt of(int id, String customCode) {
            if (customCode != null) {
                return parse(customCode);
            }
            NumFmt f = new NumFmt();
            if ((id >= 14 && id <= 17) || (id >= 27 && id <= 31) || (id >= 50 && id <= 58)) {
                f.date = true;
                return f;
            }
            if (id >= 18 && id <= 21 || id >= 45 && id <= 47 || id >= 32 && id <= 36) {
                f.time = true;
                f.seconds = id == 19 || id == 21 || id >= 45 && id <= 47;
                return f;
            }
            if (id == 22) {
                f.date = true;
                f.time = true;
                return f;
            }
            if (id > 0 && id < BUILTIN.length && BUILTIN[id] != null) {
                return parse(BUILTIN[id]);
            }
            return GENERAL;
        }

        static NumFmt parse(String code) {
            // only the first (positive) section is used
            StringBuilder sb = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        quoted = false;
                    }
                    continue;
                }
                if (c == '"') {
                    quoted = true;
                } else if (c == ';') {
                    break;
                } else if (c == '\\' || c == '_' || c == '*') {
                    i++;
                } else if (c == '[') {
                    int j = code.indexOf(']', i);
                    if (j < 0) {
                        break;
                    }
                    String inner = code.substring(i + 1, j).toLowerCase(Locale.US);
                    if (!inner.isEmpty() && (inner.charAt(0) == 'h' || inner.charAt(0) == 'm' || inner.charAt(0) == 's')) {
                        sb.append(inner.charAt(0)); // elapsed time like [h]:mm
                    }
                    i = j;
                } else {
                    sb.append(Character.toLowerCase(c));
                }
            }
            String s = sb.toString();
            if (s.isEmpty() || s.contains("general") || s.equals("@")) {
                return GENERAL;
            }
            NumFmt f = new NumFmt();
            boolean hasY = s.contains("y");
            boolean hasD = s.contains("d");
            boolean hasH = s.contains("h") || s.contains("am/pm") || s.contains("a/p");
            boolean hasS = s.contains("s");
            if (hasY || hasD) {
                f.date = true;
            }
            if (hasH || hasS) {
                f.time = true;
                f.seconds = hasS;
            }
            if (!f.date && !f.time && s.contains("m") && !s.contains("0") && !s.contains("#")) {
                f.date = true;
            }
            if (f.date || f.time) {
                int iy = s.indexOf('y');
                int id = s.indexOf('d');
                int firstHour = s.indexOf('h');
                int im = -1;
                for (int i = 0; i < s.length(); i++) {
                    // an "m" right after the hour is minutes, not the month
                    if (s.charAt(i) == 'm' && !(firstHour >= 0 && i > firstHour && i - firstHour <= 3)) {
                        im = i;
                        break;
                    }
                }
                if (iy >= 0 && (id < 0 || iy < id) && (im < 0 || iy < im)) {
                    f.dateOrder = 1;
                } else if (im >= 0 && id >= 0 && im < id && (iy < 0 || iy > im)) {
                    f.dateOrder = 2;
                }
                return f;
            }
            f.percent = s.contains("%");
            f.scientific = s.contains("e+") || s.contains("e-");
            int dot = s.indexOf('.');
            String integerPart = dot >= 0 ? s.substring(0, dot) : s;
            if (dot >= 0) {
                int count = 0;
                for (int i = dot + 1; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c == '0' || c == '#' || c == '?') {
                        count++;
                    } else {
                        break;
                    }
                }
                f.decimals = count;
            } else if (s.contains("0") || s.contains("#")) {
                f.decimals = 0;
            }
            for (int i = 1; i < integerPart.length() - 1; i++) {
                if (integerPart.charAt(i) == ',' && "0#?".indexOf(integerPart.charAt(i - 1)) >= 0 && "0#?".indexOf(integerPart.charAt(i + 1)) >= 0) {
                    f.thousands = true;
                    break;
                }
            }
            return f;
        }

        public String format(double v, boolean date1904) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return "#NUM!";
            }
            if (date || time) {
                return formatDate(v, date1904);
            }
            if (scientific) {
                return String.format(Locale.US, "%." + Math.max(0, decimals) + "E", v);
            }
            if (decimals >= 0) {
                double value = percent ? v * 100 : v;
                BigDecimal bd = new BigDecimal(Double.toString(value)).setScale(decimals, RoundingMode.HALF_UP);
                String s = bd.toPlainString();
                if (bd.signum() == 0 && s.startsWith("-")) {
                    s = s.substring(1);
                }
                if (thousands) {
                    s = group(s);
                }
                return percent ? s + "%" : s;
            }
            if (percent) {
                return general(v * 100) + "%";
            }
            return general(v);
        }

        private static String group(String s) {
            boolean negative = s.startsWith("-");
            if (negative) {
                s = s.substring(1);
            }
            int dot = s.indexOf('.');
            String integer = dot >= 0 ? s.substring(0, dot) : s;
            String rest = dot >= 0 ? s.substring(dot) : "";
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < integer.length(); i++) {
                if (i > 0 && (integer.length() - i) % 3 == 0) {
                    out.append(',');
                }
                out.append(integer.charAt(i));
            }
            return (negative ? "-" : "") + out + rest;
        }

        public static String general(double v) {
            if (v == Math.rint(v) && Math.abs(v) < 1e15) {
                return Long.toString((long) v);
            }
            double a = Math.abs(v);
            if (a >= 1e15 || (a != 0 && a < 1e-5)) {
                String s = String.format(Locale.US, "%.5E", v);
                int e = s.indexOf('E');
                String mantissa = s.substring(0, e);
                if (mantissa.contains(".")) {
                    mantissa = mantissa.replaceAll("0+$", "").replaceAll("\\.$", "");
                }
                return mantissa + s.substring(e);
            }
            return new BigDecimal(Double.toString(v)).round(new MathContext(10)).stripTrailingZeros().toPlainString();
        }

        private String formatDate(double serial, boolean date1904) {
            double days = date1904 ? serial + 1462 : serial;
            long millis = Math.round((days - 25569.0) * 86400000.0);
            if (!date1904 && serial < 61) {
                millis += 86400000L; // Excel's non-existent 29 Feb 1900
            }
            millis = Math.round(millis / 1000.0) * 1000L;
            GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US);
            c.setTimeInMillis(millis);
            int day = c.get(Calendar.DAY_OF_MONTH);
            int month = c.get(Calendar.MONTH) + 1;
            int year = c.get(Calendar.YEAR);
            String datePart;
            if (dateOrder == 1) {
                datePart = String.format(Locale.US, "%04d-%02d-%02d", year, month, day);
            } else if (dateOrder == 2) {
                datePart = String.format(Locale.US, "%02d/%02d/%04d", month, day, year);
            } else {
                datePart = String.format(Locale.US, "%02d.%02d.%04d", day, month, year);
            }
            String timePart = seconds
                    ? String.format(Locale.US, "%02d:%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
                    : String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
            if (date && time) {
                return datePart + " " + timePart;
            }
            return time ? timePart : datePart;
        }
    }

    // ------------------------------------------------------------------ helpers

    static int parseInt(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String letters(int n, boolean upper) {
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            n--;
            sb.insert(0, (char) ((upper ? 'A' : 'a') + n % 26));
            n /= 26;
        }
        return sb.toString();
    }

    private static String roman(int n) {
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length && n > 0; i++) {
            while (n >= values[i]) {
                sb.append(symbols[i]);
                n -= values[i];
            }
        }
        return sb.toString();
    }

    private ParadoksDocParsers() {
    }
}
