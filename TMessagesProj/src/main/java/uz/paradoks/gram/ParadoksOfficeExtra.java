package uz.paradoks.gram;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * More document formats for the in-app viewer: PowerPoint (.pptx), OpenDocument text/presentation/spreadsheet
 * (.odt, .odp, .ods), RTF and the old binary Word (.doc). Everything is read-only and is turned into the same
 * models (Doc / Sheet) the viewer already shows. No external libraries.
 */
public final class ParadoksOfficeExtra {

    private static final int MAX_SLIDES = 600;
    private static final int MAX_BLOCKS = 30000;
    private static final int MAX_IMAGES = 300;

    private static String lname(String local, String q) {
        if (local != null && !local.isEmpty()) {
            return local;
        }
        int c = q == null ? -1 : q.indexOf(':');
        return c >= 0 ? q.substring(c + 1) : q;
    }

    private static String att(Attributes a, String local) {
        for (int i = 0; i < a.getLength(); i++) {
            if (local.equals(lname(a.getLocalName(i), a.getQName(i)))) {
                return a.getValue(i);
            }
        }
        return null;
    }

    private static ParadoksDocParsers.Block paragraph(String text, boolean bold, boolean italic, int heading, String prefix) {
        ParadoksDocParsers.Block b = new ParadoksDocParsers.Block();
        b.type = ParadoksDocParsers.Block.PARAGRAPH;
        b.heading = heading;
        b.prefix = prefix == null ? "" : prefix;
        b.runs.add(new ParadoksDocParsers.Run(text, bold, italic, false, false));
        return b;
    }

    // ================================================================== PPTX

    /** Slide number from "ppt/slides/slide12.xml", or -1. */
    private static int slideNumber(String name) {
        if (!name.startsWith("ppt/slides/slide") || !name.endsWith(".xml")) {
            return -1;
        }
        String n = name.substring("ppt/slides/slide".length(), name.length() - 4);
        try {
            return Integer.parseInt(n);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final class Rels extends ParadoksDocParsers.Base {
        final HashMap<String, String> target = new HashMap<>();
        String notes;

        @Override
        public void startElement(String uri, String local, String q, Attributes a) {
            if ("Relationship".equals(lname(local, q))) {
                String id = att(a, "Id");
                String t = att(a, "Target");
                String type = att(a, "Type");
                if (id != null && t != null) {
                    target.put(id, t);
                    if (type != null && type.endsWith("/notesSlide")) {
                        notes = t;
                    }
                }
            }
        }
    }

    private static String resolve(String baseDir, String target) {
        if (target == null) {
            return null;
        }
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        String path = baseDir + target;
        while (path.contains("/../")) {
            int i = path.indexOf("/../");
            int prev = path.lastIndexOf('/', i - 1);
            path = (prev >= 0 ? path.substring(0, prev) : "") + path.substring(i + 3);
        }
        return path;
    }

    private static final class SlideHandler extends ParadoksDocParsers.Base {
        final ParadoksDocParsers.Doc doc;
        final HashMap<String, String> rels;
        final boolean notesMode;
        int images;
        boolean inT;
        int fldDepth;
        boolean inTable;
        boolean titleShape;
        boolean bullet;
        boolean bold;
        final StringBuilder para = new StringBuilder();
        final StringBuilder cell = new StringBuilder();
        ParadoksDocParsers.Block table;
        ArrayList<String> row;
        final StringBuilder notesText = new StringBuilder();

        SlideHandler(ParadoksDocParsers.Doc doc, HashMap<String, String> rels, boolean notesMode) {
            this.doc = doc;
            this.rels = rels;
            this.notesMode = notesMode;
        }

        private boolean full() {
            if (doc.blocks.size() >= MAX_BLOCKS) {
                doc.truncated = true;
                return true;
            }
            return false;
        }

        @Override
        public void startElement(String uri, String local, String q, Attributes a) throws SAXException {
            String n = lname(local, q);
            switch (n) {
                case "sp":
                case "pic":
                    titleShape = false;
                    break;
                case "ph": {
                    String type = att(a, "type");
                    if ("title".equals(type) || "ctrTitle".equals(type)) {
                        titleShape = true;
                    }
                    break;
                }
                case "tbl":
                    inTable = true;
                    table = new ParadoksDocParsers.Block();
                    table.type = ParadoksDocParsers.Block.TABLE;
                    break;
                case "tr":
                    row = new ArrayList<>();
                    break;
                case "tc":
                    cell.setLength(0);
                    break;
                case "p":
                    para.setLength(0);
                    bullet = false;
                    break;
                case "buChar":
                case "buAutoNum":
                    bullet = true;
                    break;
                case "rPr":
                    bold = "1".equals(att(a, "b")) || "true".equals(att(a, "b"));
                    break;
                case "fld":
                    fldDepth++;
                    break;
                case "t":
                    inT = fldDepth == 0;
                    break;
                case "br":
                    if (inTable) {
                        cell.append(' ');
                    } else {
                        para.append('\n');
                    }
                    break;
                case "blip": {
                    if (notesMode || full() || images >= MAX_IMAGES) {
                        break;
                    }
                    String id = att(a, "embed");
                    String path = id == null ? null : resolve("ppt/slides/", rels.get(id));
                    if (path != null && ParadoksDocParsers.isDecodableImage(path)) {
                        ParadoksDocParsers.Block b = new ParadoksDocParsers.Block();
                        b.type = ParadoksDocParsers.Block.IMAGE;
                        b.imagePath = path;
                        doc.blocks.add(b);
                        images++;
                    }
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (!inT) {
                return;
            }
            if (inTable) {
                cell.append(ch, start, length);
            } else {
                para.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String local, String q) {
            String n = lname(local, q);
            switch (n) {
                case "t":
                    inT = false;
                    break;
                case "fld":
                    if (fldDepth > 0) {
                        fldDepth--;
                    }
                    break;
                case "p": {
                    if (inTable) {
                        cell.append(' ');
                        break;
                    }
                    String text = para.toString().trim();
                    if (text.isEmpty()) {
                        break;
                    }
                    if (notesMode) {
                        if (notesText.length() > 0) {
                            notesText.append('\n');
                        }
                        notesText.append(text);
                    } else if (!full()) {
                        doc.blocks.add(paragraph(text, bold || titleShape, false, titleShape ? 2 : 0, bullet ? "• " : ""));
                    }
                    break;
                }
                case "tc":
                    if (row != null) {
                        row.add(cell.toString().trim());
                    }
                    break;
                case "tr":
                    if (table != null && row != null) {
                        table.table.add(row);
                    }
                    row = null;
                    break;
                case "tbl":
                    if (table != null && !table.table.isEmpty() && !full() && !notesMode) {
                        doc.blocks.add(table);
                    }
                    table = null;
                    inTable = false;
                    break;
                default:
                    break;
            }
        }
    }

    public static ParadoksDocParsers.Doc readPptx(File file) throws IOException {
        if (file.length() > ParadoksDocParsers.MAX_FILE_BYTES) {
            throw new ParadoksDocParsers.ParseException("Fayl juda katta");
        }
        ParadoksDocParsers.Doc doc = new ParadoksDocParsers.Doc();
        try (ZipFile zip = new ZipFile(file)) {
            ArrayList<Integer> numbers = new ArrayList<>();
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                int n = slideNumber(en.nextElement().getName());
                if (n > 0) {
                    numbers.add(n);
                }
            }
            if (numbers.isEmpty()) {
                throw new ParadoksDocParsers.ParseException("Bu PowerPoint (.pptx) fayli emas yoki buzilgan");
            }
            Collections.sort(numbers);
            int index = 0;
            for (int num : numbers) {
                if (index >= MAX_SLIDES) {
                    doc.truncated = true;
                    break;
                }
                index++;
                ParadoksDocParsers.Block head = paragraph("Slayd " + index, true, false, 1, "");
                doc.blocks.add(head);
                Rels rels = new Rels();
                ZipEntry relEntry = zip.getEntry("ppt/slides/_rels/slide" + num + ".xml.rels");
                if (relEntry != null) {
                    ParadoksDocParsers.sax(ParadoksDocParsers.open(zip, relEntry), rels);
                }
                SlideHandler h = new SlideHandler(doc, rels.target, false);
                ParadoksDocParsers.sax(ParadoksDocParsers.open(zip, zip.getEntry("ppt/slides/slide" + num + ".xml")), h);
                if (rels.notes != null) {
                    ZipEntry ne = zip.getEntry(resolve("ppt/slides/", rels.notes));
                    if (ne != null) {
                        SlideHandler nh = new SlideHandler(doc, rels.target, true);
                        ParadoksDocParsers.sax(ParadoksDocParsers.open(zip, ne), nh);
                        if (nh.notesText.length() > 0) {
                            doc.blocks.add(paragraph("Izoh: " + nh.notesText, false, true, 0, ""));
                        }
                    }
                }
                if (doc.blocks.size() >= MAX_BLOCKS) {
                    doc.truncated = true;
                    break;
                }
            }
        }
        return doc;
    }

    // ================================================================== ODF (odt, odp, ods)

    private static final class OdfHandler extends ParadoksDocParsers.Base {
        final boolean odp;
        final ParadoksDocParsers.Doc doc = new ParadoksDocParsers.Doc();
        final HashMap<String, boolean[]> styles = new HashMap<>(); // name -> {bold, italic, underline}
        String styleName;
        int slide;
        int images;
        // paragraph state
        boolean inPara;
        int heading;
        final ArrayList<ParadoksDocParsers.Run> runs = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        final ArrayList<boolean[]> spanStack = new ArrayList<>();
        int listDepth;
        boolean itemStart;
        // table state
        int tableDepth;
        ParadoksDocParsers.Block table;
        ArrayList<String> row;
        final StringBuilder cell = new StringBuilder();
        boolean inNotes;

        OdfHandler(boolean odp) {
            this.odp = odp;
        }

        private boolean[] fmt() {
            return spanStack.isEmpty() ? new boolean[3] : spanStack.get(spanStack.size() - 1);
        }

        private void flushText() {
            if (text.length() == 0) {
                return;
            }
            boolean[] f = fmt();
            runs.add(new ParadoksDocParsers.Run(text.toString(), f[0], f[1], f[2], false));
            text.setLength(0);
        }

        private void add(String s) {
            if (tableDepth > 0) {
                cell.append(s);
            } else {
                text.append(s);
            }
        }

        @Override
        public void startElement(String uri, String local, String q, Attributes a) throws SAXException {
            String n = lname(local, q);
            switch (n) {
                case "style":
                    styleName = att(a, "name");
                    if (styleName != null) {
                        styles.put(styleName, new boolean[3]);
                    }
                    break;
                case "text-properties":
                    if (styleName != null) {
                        boolean[] f = styles.get(styleName);
                        String w = att(a, "font-weight");
                        String s = att(a, "font-style");
                        String u = att(a, "text-underline-style");
                        if (f != null) {
                            f[0] = "bold".equals(w);
                            f[1] = "italic".equals(s);
                            f[2] = u != null && !"none".equals(u);
                        }
                    }
                    break;
                case "page":
                    if (odp && !inNotes) {
                        slide++;
                        if (slide > MAX_SLIDES) {
                            doc.truncated = true;
                            stop();
                        }
                        doc.blocks.add(paragraph("Slayd " + slide, true, false, 1, ""));
                    }
                    break;
                case "notes":
                    inNotes = true;
                    break;
                case "list-item":
                    listDepth++;
                    itemStart = true;
                    break;
                case "table":
                    tableDepth++;
                    if (tableDepth == 1) {
                        table = new ParadoksDocParsers.Block();
                        table.type = ParadoksDocParsers.Block.TABLE;
                    }
                    break;
                case "table-row":
                    if (tableDepth == 1) {
                        row = new ArrayList<>();
                    }
                    break;
                case "table-cell":
                    if (tableDepth == 1) {
                        cell.setLength(0);
                    }
                    break;
                case "h":
                case "p":
                    if (!inPara) {
                        inPara = true;
                        runs.clear();
                        text.setLength(0);
                        spanStack.clear();
                        String sn = att(a, "style-name");
                        boolean[] f = sn == null ? null : styles.get(sn);
                        spanStack.add(f == null ? new boolean[3] : f);
                        heading = 0;
                        if (n.equals("h")) {
                            String lvl = att(a, "outline-level");
                            heading = Math.max(1, Math.min(6, ParadoksDocParsers.parseInt(lvl, 1)));
                        }
                    }
                    break;
                case "span": {
                    if (inPara && tableDepth == 0) {
                        flushText();
                        String sn = att(a, "style-name");
                        boolean[] base = fmt();
                        boolean[] f = sn == null ? null : styles.get(sn);
                        boolean[] merged = new boolean[]{base[0] || (f != null && f[0]), base[1] || (f != null && f[1]), base[2] || (f != null && f[2])};
                        spanStack.add(merged);
                    }
                    break;
                }
                case "s": {
                    int c = ParadoksDocParsers.parseInt(att(a, "c"), 1);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(c, 50); i++) {
                        sb.append(' ');
                    }
                    add(sb.toString());
                    break;
                }
                case "tab":
                    add("    ");
                    break;
                case "line-break":
                    add(tableDepth > 0 ? " " : "\n");
                    break;
                case "image": {
                    String href = att(a, "href");
                    if (href != null && tableDepth == 0 && images < MAX_IMAGES && doc.blocks.size() < MAX_BLOCKS
                            && ParadoksDocParsers.isDecodableImage(href)) {
                        ParadoksDocParsers.Block b = new ParadoksDocParsers.Block();
                        b.type = ParadoksDocParsers.Block.IMAGE;
                        b.imagePath = href;
                        doc.blocks.add(b);
                        images++;
                    }
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inPara) {
                add(new String(ch, start, length));
            }
        }

        @Override
        public void endElement(String uri, String local, String q) {
            String n = lname(local, q);
            switch (n) {
                case "style":
                    styleName = null;
                    break;
                case "notes":
                    inNotes = false;
                    break;
                case "list-item":
                    if (listDepth > 0) {
                        listDepth--;
                    }
                    break;
                case "span":
                    if (inPara && tableDepth == 0) {
                        flushText();
                        if (spanStack.size() > 1) {
                            spanStack.remove(spanStack.size() - 1);
                        }
                    }
                    break;
                case "h":
                case "p":
                    if (!inPara) {
                        break;
                    }
                    inPara = false;
                    if (tableDepth > 0) {
                        cell.append(' ');
                        break;
                    }
                    flushText();
                    boolean empty = true;
                    for (ParadoksDocParsers.Run r : runs) {
                        if (!r.text.trim().isEmpty()) {
                            empty = false;
                            break;
                        }
                    }
                    if (!empty && !inNotes && doc.blocks.size() < MAX_BLOCKS) {
                        ParadoksDocParsers.Block b = new ParadoksDocParsers.Block();
                        b.type = ParadoksDocParsers.Block.PARAGRAPH;
                        b.heading = heading;
                        b.prefix = listDepth > 0 ? "• " : "";
                        b.runs.addAll(runs);
                        doc.blocks.add(b);
                    } else if (doc.blocks.size() >= MAX_BLOCKS) {
                        doc.truncated = true;
                    }
                    runs.clear();
                    break;
                case "table-cell":
                    if (tableDepth == 1 && row != null) {
                        row.add(cell.toString().trim());
                    }
                    break;
                case "table-row":
                    if (tableDepth == 1 && row != null && table != null) {
                        table.table.add(row);
                        row = null;
                    }
                    break;
                case "table":
                    if (tableDepth == 1 && table != null) {
                        if (!table.table.isEmpty() && doc.blocks.size() < MAX_BLOCKS) {
                            doc.blocks.add(table);
                        }
                        table = null;
                    }
                    if (tableDepth > 0) {
                        tableDepth--;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static ParadoksDocParsers.Doc readOdfDoc(File file, boolean odp) throws IOException {
        if (file.length() > ParadoksDocParsers.MAX_FILE_BYTES) {
            throw new ParadoksDocParsers.ParseException("Fayl juda katta");
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("content.xml");
            if (entry == null) {
                throw new ParadoksDocParsers.ParseException("Bu OpenDocument fayli emas yoki buzilgan");
            }
            OdfHandler h = new OdfHandler(odp);
            ParadoksDocParsers.sax(ParadoksDocParsers.open(zip, entry), h);
            return h.doc;
        }
    }

    public static ParadoksDocParsers.Doc readOdt(File file) throws IOException {
        return readOdfDoc(file, false);
    }

    public static ParadoksDocParsers.Doc readOdp(File file) throws IOException {
        return readOdfDoc(file, true);
    }

    private static final class OdsHandler extends ParadoksDocParsers.Base {
        final ArrayList<ParadoksDocParsers.Sheet> sheets = new ArrayList<>();
        ParadoksDocParsers.Sheet sheet;
        ArrayList<String> row;
        int rowRepeat;
        int colRepeat;
        String cellValue;
        boolean inCell;
        boolean inP;
        final StringBuilder cell = new StringBuilder();
        int paragraphs;
        int depth;

        @Override
        public void startElement(String uri, String local, String q, Attributes a) {
            String n = lname(local, q);
            switch (n) {
                case "table":
                    if (depth == 0) {
                        sheet = new ParadoksDocParsers.Sheet();
                        String name = att(a, "name");
                        sheet.name = name == null ? "Sheet" + (sheets.size() + 1) : name;
                    }
                    depth++;
                    break;
                case "table-row":
                    row = new ArrayList<>();
                    rowRepeat = Math.max(1, ParadoksDocParsers.parseInt(att(a, "number-rows-repeated"), 1));
                    break;
                case "table-cell":
                case "covered-table-cell":
                    inCell = true;
                    cell.setLength(0);
                    paragraphs = 0;
                    colRepeat = Math.max(1, ParadoksDocParsers.parseInt(att(a, "number-columns-repeated"), 1));
                    cellValue = att(a, "value");
                    if (cellValue == null) {
                        cellValue = att(a, "string-value");
                    }
                    if (cellValue == null) {
                        cellValue = att(a, "date-value");
                    }
                    break;
                case "p":
                    if (inCell) {
                        inP = true;
                        if (paragraphs++ > 0) {
                            cell.append('\n');
                        }
                    }
                    break;
                case "s":
                    if (inP) {
                        cell.append(' ');
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inP) {
                cell.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String local, String q) {
            String n = lname(local, q);
            switch (n) {
                case "p":
                    inP = false;
                    break;
                case "table-cell":
                case "covered-table-cell": {
                    inCell = false;
                    String v = cell.length() > 0 ? cell.toString() : (cellValue == null ? "" : cellValue);
                    if (row != null) {
                        int rep = v.isEmpty() ? Math.min(colRepeat, 3) : Math.min(colRepeat, 50);
                        for (int i = 0; i < rep && row.size() < ParadoksDocParsers.MAX_COLS; i++) {
                            row.add(v);
                        }
                        if (row.size() >= ParadoksDocParsers.MAX_COLS) {
                            sheet.truncatedCols = true;
                        }
                    }
                    break;
                }
                case "table-row": {
                    if (row == null || sheet == null) {
                        break;
                    }
                    int last = row.size();
                    while (last > 0 && row.get(last - 1).isEmpty()) {
                        last--;
                    }
                    String[] cells = new String[last];
                    for (int i = 0; i < last; i++) {
                        cells[i] = row.get(i);
                    }
                    int rep = last == 0 ? Math.min(rowRepeat, 2) : Math.min(rowRepeat, 20);
                    for (int i = 0; i < rep; i++) {
                        if (sheet.rows.size() >= ParadoksDocParsers.MAX_ROWS) {
                            sheet.truncatedRows = true;
                            break;
                        }
                        sheet.rows.add(cells);
                        sheet.cols = Math.max(sheet.cols, last);
                    }
                    row = null;
                    break;
                }
                case "table":
                    depth--;
                    if (depth == 0 && sheet != null) {
                        while (!sheet.rows.isEmpty() && sheet.rows.get(sheet.rows.size() - 1).length == 0) {
                            sheet.rows.remove(sheet.rows.size() - 1);
                        }
                        sheets.add(sheet);
                        sheet = null;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    public static ArrayList<ParadoksDocParsers.Sheet> readOds(File file) throws IOException {
        if (file.length() > ParadoksDocParsers.MAX_FILE_BYTES) {
            throw new ParadoksDocParsers.ParseException("Fayl juda katta");
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("content.xml");
            if (entry == null) {
                throw new ParadoksDocParsers.ParseException("Bu OpenDocument jadvali emas yoki buzilgan");
            }
            OdsHandler h = new OdsHandler();
            ParadoksDocParsers.sax(ParadoksDocParsers.open(zip, entry), h);
            if (h.sheets.isEmpty()) {
                throw new ParadoksDocParsers.ParseException("Jadvalda varaq topilmadi");
            }
            return h.sheets;
        }
    }

    // ================================================================== RTF

    public static ParadoksDocParsers.Doc readRtf(File file) throws IOException {
        byte[] data = ParadoksXlsReader.readAll(file);
        String s = new String(data, "ISO-8859-1");
        if (!s.startsWith("{\\rtf")) {
            throw new ParadoksDocParsers.ParseException("Bu RTF fayli emas yoki buzilgan");
        }
        ParadoksDocParsers.Doc doc = new ParadoksDocParsers.Doc();
        final java.util.ArrayDeque<boolean[]> stack = new java.util.ArrayDeque<>(); // {skip, bold, italic, underline}
        boolean[] cur = new boolean[4];
        ArrayList<ParadoksDocParsers.Run> runs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        boolean[] runFmt = new boolean[3];
        int uc = 1;
        int skipChars = 0;
        int i = 0;
        int n = s.length();
        boolean starPending = false;
        while (i < n) {
            char c = s.charAt(i);
            if (skipChars > 0 && c != '\\' && c != '{' && c != '}') {
                skipChars--;
                i++;
                continue;
            }
            if (c == '{') {
                stack.push(cur.clone());
                i++;
                starPending = true;
                continue;
            }
            if (c == '}') {
                if (!stack.isEmpty()) {
                    // flush text with the state of the closing group
                    flushRtf(text, runs, runFmt);
                    cur = stack.pop();
                }
                i++;
                continue;
            }
            if (c == '\\') {
                i++;
                if (i >= n) {
                    break;
                }
                char d = s.charAt(i);
                if (d == '\\' || d == '{' || d == '}') {
                    if (!cur[0]) {
                        setFmt(text, runs, runFmt, cur);
                        text.append(d);
                    }
                    i++;
                    starPending = false;
                    continue;
                }
                if (d == '*') {
                    cur[0] = true; // unknown destination: skip it
                    i++;
                    continue;
                }
                if (d == '\'') {
                    if (i + 2 < n) {
                        try {
                            int v = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                            if (!cur[0] && skipChars == 0) {
                                setFmt(text, runs, runFmt, cur);
                                text.append(cp1252(v));
                            }
                        } catch (Exception ignore) {
                        }
                    }
                    i += 3;
                    if (skipChars > 0) {
                        skipChars--;
                    }
                    continue;
                }
                if (d == '~') {
                    if (!cur[0]) {
                        text.append(' ');
                    }
                    i++;
                    continue;
                }
                if (d == '-' || d == '_' || d == '\n' || d == '\r') {
                    if (d == '_' && !cur[0]) {
                        text.append('-');
                    }
                    i++;
                    continue;
                }
                if (!Character.isLetter(d)) {
                    i++;
                    continue;
                }
                int st = i;
                while (i < n && Character.isLetter(s.charAt(i))) {
                    i++;
                }
                String word = s.substring(st, i);
                int num = 0;
                boolean hasNum = false;
                boolean neg = false;
                if (i < n && s.charAt(i) == '-') {
                    neg = true;
                    i++;
                }
                int ns = i;
                while (i < n && Character.isDigit(s.charAt(i)) && i - ns < 9) {
                    num = num * 10 + (s.charAt(i) - '0');
                    hasNum = true;
                    i++;
                }
                if (neg) {
                    num = -num;
                }
                if (i < n && s.charAt(i) == ' ') {
                    i++;
                }
                switch (word) {
                    case "fonttbl":
                    case "colortbl":
                    case "stylesheet":
                    case "info":
                    case "pict":
                    case "header":
                    case "footer":
                    case "headerl":
                    case "headerr":
                    case "footerl":
                    case "footerr":
                    case "footnote":
                    case "object":
                    case "themedata":
                    case "colorschememapping":
                    case "latentstyles":
                    case "datastore":
                    case "xmlnstbl":
                    case "listtable":
                    case "listoverridetable":
                    case "rsidtbl":
                    case "generator":
                        cur[0] = true;
                        break;
                    case "par":
                    case "sect":
                    case "page":
                        if (!cur[0]) {
                            flushRtf(text, runs, runFmt);
                            endRtfParagraph(doc, runs);
                        }
                        break;
                    case "line":
                        if (!cur[0]) {
                            text.append('\n');
                        }
                        break;
                    case "tab":
                        if (!cur[0]) {
                            text.append("    ");
                        }
                        break;
                    case "cell":
                        if (!cur[0]) {
                            text.append("  |  ");
                        }
                        break;
                    case "row":
                        if (!cur[0]) {
                            flushRtf(text, runs, runFmt);
                            endRtfParagraph(doc, runs);
                        }
                        break;
                    case "emdash":
                        if (!cur[0]) text.append('—');
                        break;
                    case "endash":
                        if (!cur[0]) text.append('–');
                        break;
                    case "bullet":
                        if (!cur[0]) text.append('•');
                        break;
                    case "lquote":
                        if (!cur[0]) text.append('‘');
                        break;
                    case "rquote":
                        if (!cur[0]) text.append('’');
                        break;
                    case "ldblquote":
                        if (!cur[0]) text.append('“');
                        break;
                    case "rdblquote":
                        if (!cur[0]) text.append('”');
                        break;
                    case "uc":
                        uc = hasNum ? num : 1;
                        break;
                    case "u": {
                        if (!cur[0]) {
                            setFmt(text, runs, runFmt, cur);
                            text.append((char) (num < 0 ? num + 65536 : num));
                        }
                        skipChars = uc;
                        break;
                    }
                    case "b":
                        flushRtf(text, runs, runFmt);
                        cur[1] = !hasNum || num != 0;
                        break;
                    case "i":
                        flushRtf(text, runs, runFmt);
                        cur[2] = !hasNum || num != 0;
                        break;
                    case "ul":
                        flushRtf(text, runs, runFmt);
                        cur[3] = !hasNum || num != 0;
                        break;
                    case "ulnone":
                        flushRtf(text, runs, runFmt);
                        cur[3] = false;
                        break;
                    case "plain":
                        flushRtf(text, runs, runFmt);
                        cur[1] = cur[2] = cur[3] = false;
                        break;
                    default:
                        break;
                }
                starPending = false;
                continue;
            }
            if (c == '\r' || c == '\n') {
                i++;
                continue;
            }
            if (!cur[0]) {
                setFmt(text, runs, runFmt, cur);
                text.append(c);
            }
            i++;
            if (doc.blocks.size() >= MAX_BLOCKS) {
                doc.truncated = true;
                break;
            }
        }
        flushRtf(text, runs, runFmt);
        endRtfParagraph(doc, runs);
        if (doc.blocks.isEmpty()) {
            throw new ParadoksDocParsers.ParseException("Hujjatda matn topilmadi");
        }
        return doc;
    }

    private static void setFmt(StringBuilder text, ArrayList<ParadoksDocParsers.Run> runs, boolean[] runFmt, boolean[] cur) {
        if (runFmt[0] != cur[1] || runFmt[1] != cur[2] || runFmt[2] != cur[3]) {
            flushRtf(text, runs, runFmt);
            runFmt[0] = cur[1];
            runFmt[1] = cur[2];
            runFmt[2] = cur[3];
        }
    }

    private static void flushRtf(StringBuilder text, ArrayList<ParadoksDocParsers.Run> runs, boolean[] runFmt) {
        if (text.length() > 0) {
            runs.add(new ParadoksDocParsers.Run(text.toString(), runFmt[0], runFmt[1], runFmt[2], false));
            text.setLength(0);
        }
    }

    private static void endRtfParagraph(ParadoksDocParsers.Doc doc, ArrayList<ParadoksDocParsers.Run> runs) {
        boolean empty = true;
        for (ParadoksDocParsers.Run r : runs) {
            if (!r.text.trim().isEmpty()) {
                empty = false;
                break;
            }
        }
        if (!empty && doc.blocks.size() < MAX_BLOCKS) {
            ParadoksDocParsers.Block b = new ParadoksDocParsers.Block();
            b.type = ParadoksDocParsers.Block.PARAGRAPH;
            b.runs.addAll(runs);
            doc.blocks.add(b);
        }
        runs.clear();
    }

    private static char cp1252(int v) {
        if (v >= 0x80 && v < 0xA0) {
            try {
                String t = new String(new byte[]{(byte) v}, "windows-1252");
                return t.isEmpty() ? '?' : t.charAt(0);
            } catch (Exception e) {
                return '?';
            }
        }
        return (char) v;
    }

    // ================================================================== DOC (Word 97-2003)

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
    }

    public static ParadoksDocParsers.Doc readDoc(File file) throws IOException {
        byte[] all = ParadoksXlsReader.readAll(file);
        byte[] word;
        try {
            word = ParadoksXlsReader.stream(all, new String[]{"WordDocument"});
        } catch (ParadoksDocParsers.ParseException e) {
            throw new ParadoksDocParsers.ParseException("Bu Word (.doc) fayli emas yoki buzilgan");
        }
        if (word.length < 0x1A8 || u16(word, 0) != 0xA5EC) {
            throw new ParadoksDocParsers.ParseException("Bu Word (.doc) fayli emas yoki buzilgan");
        }
        int flags = u16(word, 0x0A);
        if ((flags & 0x0100) != 0) {
            throw new SecurityException("encrypted");
        }
        String tableName = (flags & 0x0200) != 0 ? "1Table" : "0Table";
        byte[] table;
        try {
            table = ParadoksXlsReader.stream(all, new String[]{tableName});
        } catch (ParadoksDocParsers.ParseException e) {
            throw new ParadoksDocParsers.ParseException("Word jadvallari (piece table) topilmadi");
        }
        int csw = u16(word, 0x20);
        int lwStart = 0x22 + csw * 2 + 2;
        int ccpText = i32(word, lwStart + 12);
        int cslw = u16(word, 0x22 + csw * 2);
        int fcLcb = lwStart + cslw * 4 + 2;
        int fcClx = i32(word, fcLcb + 33 * 8);
        int lcbClx = i32(word, fcLcb + 33 * 8 + 4);
        if (ccpText <= 0 || fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > table.length) {
            throw new ParadoksDocParsers.ParseException("Word tuzilishi o'qilmadi");
        }
        int p = fcClx;
        int end = fcClx + lcbClx;
        while (p < end && (table[p] & 0xFF) == 1) {
            p += 3 + u16(table, p + 1);
        }
        if (p >= end || (table[p] & 0xFF) != 2) {
            throw new ParadoksDocParsers.ParseException("Word matn bo'laklari topilmadi");
        }
        int lcb = i32(table, p + 1);
        int plc = p + 5;
        int pieces = (lcb - 4) / 12;
        if (pieces <= 0 || plc + lcb > table.length) {
            throw new ParadoksDocParsers.ParseException("Word matn bo'laklari buzilgan");
        }
        StringBuilder raw = new StringBuilder();
        for (int k = 0; k < pieces && raw.length() < ccpText; k++) {
            int cpStart = i32(table, plc + k * 4);
            int cpEnd = i32(table, plc + (k + 1) * 4);
            int fc = i32(table, plc + (pieces + 1) * 4 + k * 8 + 2);
            boolean compressed = (fc & 0x40000000) != 0;
            int off = fc & 0x3FFFFFFF;
            int len = Math.min(cpEnd - cpStart, ccpText - raw.length());
            if (len <= 0) {
                continue;
            }
            if (compressed) {
                off /= 2;
                if (off < 0 || off + len > word.length) {
                    len = Math.max(0, word.length - off);
                }
                for (int j = 0; j < len; j++) {
                    raw.append(cp1252(word[off + j] & 0xFF));
                }
            } else {
                if (off < 0 || off + len * 2 > word.length) {
                    len = Math.max(0, (word.length - off) / 2);
                }
                for (int j = 0; j < len; j++) {
                    raw.append((char) u16(word, off + j * 2));
                }
            }
        }
        // clean field codes and control characters
        ParadoksDocParsers.Doc doc = new ParadoksDocParsers.Doc();
        StringBuilder line = new StringBuilder();
        int fieldDepth = 0;
        boolean[] inResult = new boolean[16];
        for (int j = 0; j < raw.length(); j++) {
            char c = raw.charAt(j);
            if (c == 0x13) {
                if (fieldDepth < 15) {
                    fieldDepth++;
                    inResult[fieldDepth] = false;
                }
                continue;
            }
            if (c == 0x14) {
                inResult[fieldDepth] = true;
                continue;
            }
            if (c == 0x15) {
                if (fieldDepth > 0) {
                    fieldDepth--;
                }
                continue;
            }
            boolean skipping = false;
            for (int d = 1; d <= fieldDepth; d++) {
                if (!inResult[d]) {
                    skipping = true;
                    break;
                }
            }
            if (skipping) {
                continue;
            }
            if (c == '\r' || c == 0x0C) {
                addDocLine(doc, line);
            } else if (c == 0x07) {
                line.append("  |  ");
            } else if (c == 0x0B) {
                line.append('\n');
            } else if (c == 0x09) {
                line.append("    ");
            } else if (c == 0x1E) {
                line.append('-');
            } else if (c < 0x20 || c == 0xFFFF) {
                // other control characters (pictures, annotations) are dropped
            } else {
                line.append(c);
            }
            if (doc.blocks.size() >= MAX_BLOCKS) {
                doc.truncated = true;
                break;
            }
        }
        addDocLine(doc, line);
        if (doc.blocks.isEmpty()) {
            throw new ParadoksDocParsers.ParseException("Hujjatda matn topilmadi");
        }
        return doc;
    }

    private static void addDocLine(ParadoksDocParsers.Doc doc, StringBuilder line) {
        String t = line.toString().trim();
        line.setLength(0);
        if (!t.isEmpty() && !t.matches("[|\\s]+")) {
            doc.blocks.add(paragraph(t, false, false, 0, ""));
        }
    }

    // ================================================================== PPT (PowerPoint 97-2003, text only)

    public static ParadoksDocParsers.Doc readPpt(File file) throws IOException {
        byte[] all = ParadoksXlsReader.readAll(file);
        byte[] d;
        try {
            d = ParadoksXlsReader.stream(all, new String[]{"PowerPoint Document"});
        } catch (ParadoksDocParsers.ParseException e) {
            throw new ParadoksDocParsers.ParseException("Bu PowerPoint (.ppt) fayli emas yoki buzilgan");
        }
        ParadoksDocParsers.Doc doc = new ParadoksDocParsers.Doc();
        int[] slideCount = new int[1];
        walkPpt(d, 0, d.length, doc, slideCount, 0);
        if (doc.blocks.size() <= slideCount[0]) {
            // text lives in the slides themselves (some writers, LibreOffice for one, skip the text list)
            doc.blocks.clear();
            slideCount[0] = 0;
            walkPptSlides(d, 0, d.length, doc, slideCount, 0);
        }
        if (slideCount[0] == 0 || doc.blocks.size() <= slideCount[0]) {
            throw new ParadoksDocParsers.ParseException("Slaydlarda matn topilmadi");
        }
        return doc;
    }

    private static void walkPptSlides(byte[] d, int start, int limit, ParadoksDocParsers.Doc doc, int[] count, int depth) throws IOException {
        int p = start;
        while (p + 8 <= limit) {
            int verInst = u16(d, p);
            int type = u16(d, p + 2);
            long len = i32(d, p + 4) & 0xFFFFFFFFL;
            int body = p + 8;
            if (len > limit - body) {
                break;
            }
            int end = (int) (body + len);
            if (type == 0x03EE && (verInst & 0xF) == 0xF) {
                count[0]++;
                doc.blocks.add(paragraph("Slayd " + count[0], true, false, 1, ""));
                collectPptText(d, body, end, doc, 0);
            } else if ((verInst & 0xF) == 0xF && depth < 8 && type != 0x03F8 && type != 0x03F0) {
                walkPptSlides(d, body, end, doc, count, depth + 1);
            }
            p = end;
        }
    }

    private static void collectPptText(byte[] d, int start, int limit, ParadoksDocParsers.Doc doc, int depth) throws IOException {
        int p = start;
        while (p + 8 <= limit) {
            int verInst = u16(d, p);
            int type = u16(d, p + 2);
            long len = i32(d, p + 4) & 0xFFFFFFFFL;
            int body = p + 8;
            if (len > limit - body) {
                break;
            }
            int end = (int) (body + len);
            if ((verInst & 0xF) == 0xF) {
                if (depth < 16) {
                    collectPptText(d, body, end, doc, depth + 1);
                }
            } else if (type == 0x0FA0 || type == 0x0FA8) {
                String text;
                if (type == 0x0FA0) {
                    text = new String(d, body, (int) (len & ~1L), "UTF-16LE");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = body; i < end; i++) {
                        sb.append(cp1252(d[i] & 0xFF));
                    }
                    text = sb.toString();
                }
                for (String line : text.split("[\\r\\u000B\\n]")) {
                    String tl = line.trim();
                    if (!tl.isEmpty() && doc.blocks.size() < MAX_BLOCKS) {
                        doc.blocks.add(paragraph(tl, false, false, 0, ""));
                    }
                }
            }
            p = end;
        }
    }

    private static void walkPpt(byte[] d, int start, int limit, ParadoksDocParsers.Doc doc, int[] count, int depth) throws IOException {
        int p = start;
        while (p + 8 <= limit) {
            int verInst = u16(d, p);
            int type = u16(d, p + 2);
            long len = i32(d, p + 4) & 0xFFFFFFFFL;
            int body = p + 8;
            if (len > limit - body) {
                break;
            }
            int end = (int) (body + len);
            if (type == 0x0FF0 && (verInst >> 4) == 0) {
                // SlideListWithTexts for slides: SlidePersistAtom starts a slide, text atoms follow
                int q = body;
                boolean title = false;
                while (q + 8 <= end) {
                    int t = u16(d, q + 2);
                    long l = i32(d, q + 4) & 0xFFFFFFFFL;
                    int b = q + 8;
                    if (l > end - b) {
                        break;
                    }
                    int e = (int) (b + l);
                    if (t == 0x03F3) {
                        count[0]++;
                        doc.blocks.add(paragraph("Slayd " + count[0], true, false, 1, ""));
                        title = false;
                    } else if (t == 0x0F9F && l >= 4) {
                        int kind = i32(d, b);
                        title = kind == 0 || kind == 6;
                    } else if (t == 0x0FA0 || t == 0x0FA8) {
                        String text;
                        if (t == 0x0FA0) {
                            text = new String(d, b, (int) (l & ~1L), "UTF-16LE");
                        } else {
                            StringBuilder sb = new StringBuilder();
                            for (int i = b; i < e; i++) {
                                sb.append(cp1252(d[i] & 0xFF));
                            }
                            text = sb.toString();
                        }
                        for (String line : text.split("[\\r\\u000B\\n]")) {
                            String tl = line.trim();
                            if (!tl.isEmpty() && doc.blocks.size() < MAX_BLOCKS) {
                                doc.blocks.add(paragraph(tl, title, false, title ? 2 : 0, ""));
                            }
                        }
                    }
                    q = e;
                }
            }
            else if ((verInst & 0xF) == 0xF && depth < 8) {
                walkPpt(d, body, end, doc, count, depth + 1);
            }
            p = end;
        }
    }
    private ParadoksOfficeExtra() {
    }
}
