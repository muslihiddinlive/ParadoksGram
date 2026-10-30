package uz.paradoks.gram;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Reader for old binary Excel files (.xls, BIFF8 from Excel 97 to 2003) without any library.
 * The file is an OLE2 container; its "Workbook" stream holds the records. Shows the visible sheets as text,
 * using the same number/date formatting as the .xlsx reader. Formulas are shown by their stored result.
 * Password-protected files and the very old BIFF5 format (Excel 5/95) are refused with a clear message.
 */
final class ParadoksXlsReader {

    private static final long FREE = 0xFFFFFFFFL;

    static ArrayList<ParadoksDocParsers.Sheet> read(File file) throws IOException {
        byte[] data = readAll(file);
        byte[] workbook = workbookStream(data);
        return new Biff(workbook).parse();
    }

    private static byte[] readAll(File file) throws IOException {
        if (file.length() > ParadoksDocParsers.MAX_FILE_BYTES) {
            throw new ParadoksDocParsers.ParseException("Fayl juda katta");
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, file.length()));
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ little endian helpers

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
    }

    private static long i64(byte[] b, int i) {
        return (i32(b, i) & 0xFFFFFFFFL) | ((long) i32(b, i + 4) << 32);
    }

    // ------------------------------------------------------------------ OLE2 container

    private static byte[] workbookStream(byte[] d) throws IOException {
        final byte[] signature = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        if (d.length < 512) {
            throw new ParadoksDocParsers.ParseException("Bu Excel (.xls) fayli emas yoki buzilgan");
        }
        for (int i = 0; i < 8; i++) {
            if (d[i] != signature[i]) {
                throw new ParadoksDocParsers.ParseException("Bu Excel (.xls) fayli emas yoki buzilgan");
            }
        }
        int sectorShift = u16(d, 0x1E);
        int miniShift = u16(d, 0x20);
        if (sectorShift < 7 || sectorShift > 16 || miniShift < 2 || miniShift > 12) {
            throw new ParadoksDocParsers.ParseException("Fayl tuzilishi buzilgan");
        }
        final int sectorSize = 1 << sectorShift;
        final int miniSize = 1 << miniShift;
        int dirStart = i32(d, 0x30);
        int cutoff = i32(d, 0x38);
        int miniFatStart = i32(d, 0x3C);
        int difatStart = i32(d, 0x44);
        int difatCount = i32(d, 0x48);

        // sectors that hold the FAT
        ArrayList<Integer> fatSectors = new ArrayList<>();
        for (int i = 0; i < 109; i++) {
            int s = i32(d, 0x4C + i * 4);
            if (s >= 0) {
                fatSectors.add(s);
            }
        }
        int difat = difatStart;
        for (int k = 0; k < difatCount && difat >= 0; k++) {
            long off = (long) (difat + 1) * sectorSize;
            if (off + sectorSize > d.length) {
                break;
            }
            int entries = sectorSize / 4 - 1;
            for (int i = 0; i < entries; i++) {
                int s = i32(d, (int) off + i * 4);
                if (s >= 0) {
                    fatSectors.add(s);
                }
            }
            difat = i32(d, (int) off + entries * 4);
        }
        int perSector = sectorSize / 4;
        int[] fat = new int[fatSectors.size() * perSector];
        java.util.Arrays.fill(fat, -1);
        for (int i = 0; i < fatSectors.size(); i++) {
            long off = (long) (fatSectors.get(i) + 1) * sectorSize;
            if (off + sectorSize > d.length) {
                continue;
            }
            for (int j = 0; j < perSector; j++) {
                fat[i * perSector + j] = i32(d, (int) off + j * 4);
            }
        }

        byte[] directory = chain(d, fat, dirStart, sectorSize, -1);
        int rootStart = -1;
        int streamStart = -1;
        long streamSize = -1;
        boolean preferred = false;
        for (int e = 0; e + 128 <= directory.length; e += 128) {
            int nameLen = u16(directory, e + 64);
            if (nameLen < 2 || nameLen > 64) {
                continue;
            }
            String name = new String(directory, e, nameLen - 2, "UTF-16LE");
            int type = u8(directory, e + 66);
            int start = i32(directory, e + 116);
            long size = i32(directory, e + 120) & 0xFFFFFFFFL;
            if (type == 5) {
                rootStart = start;
            } else if (type == 2 && (name.equals("Workbook") || name.equals("Book"))) {
                if (!preferred) {
                    streamStart = start;
                    streamSize = size;
                    preferred = name.equals("Workbook");
                }
            }
        }
        if (streamStart < 0) {
            throw new ParadoksDocParsers.ParseException("Excel jadvali topilmadi (fayl boshqa turdagi bo'lishi mumkin)");
        }
        if (streamSize >= cutoff) {
            return chain(d, fat, streamStart, sectorSize, streamSize);
        }
        // small stream: lives in the mini stream
        byte[] miniFatBytes = chain(d, fat, miniFatStart, sectorSize, -1);
        int[] miniFat = new int[miniFatBytes.length / 4];
        for (int i = 0; i < miniFat.length; i++) {
            miniFat[i] = i32(miniFatBytes, i * 4);
        }
        byte[] miniStream = chain(d, fat, rootStart, sectorSize, -1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int s = streamStart;
        for (int guard = 0; s >= 0 && guard <= miniFat.length && out.size() < streamSize; guard++) {
            long off = (long) s * miniSize;
            if (off + miniSize > miniStream.length) {
                break;
            }
            out.write(miniStream, (int) off, miniSize);
            s = s < miniFat.length ? miniFat[s] : -1;
        }
        byte[] all = out.toByteArray();
        return all.length > streamSize ? java.util.Arrays.copyOf(all, (int) streamSize) : all;
    }

    /** Reads a sector chain; limit < 0 means "everything the chain holds". */
    private static byte[] chain(byte[] d, int[] fat, int start, int sectorSize, long limit) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int s = start;
        for (int guard = 0; s >= 0 && guard <= fat.length; guard++) {
            long off = (long) (s + 1) * sectorSize;
            if (off >= d.length) {
                break;
            }
            int len = (int) Math.min(sectorSize, d.length - off);
            out.write(d, (int) off, len);
            if (limit >= 0 && out.size() >= limit) {
                break;
            }
            s = s < fat.length ? fat[s] : -1;
        }
        byte[] all = out.toByteArray();
        if (limit >= 0 && all.length > limit) {
            return java.util.Arrays.copyOf(all, (int) limit);
        }
        return all;
    }

    // ------------------------------------------------------------------ BIFF8 records

    /** Reads the string table across CONTINUE records (a flag byte starts each continued piece of characters). */
    private static final class ChunkReader {
        private final ArrayList<byte[]> chunks;
        private int index;
        private byte[] cur;
        private int pos;

        ChunkReader(ArrayList<byte[]> chunks) {
            this.chunks = chunks;
            this.cur = chunks.get(0);
        }

        boolean hasMore() {
            while (pos >= cur.length) {
                if (index + 1 >= chunks.size()) {
                    return false;
                }
                cur = chunks.get(++index);
                pos = 0;
            }
            return true;
        }

        int u8() throws IOException {
            if (!hasMore()) {
                throw new IOException("eof");
            }
            return cur[pos++] & 0xFF;
        }

        int u16() throws IOException {
            int lo = u8();
            return lo | (u8() << 8);
        }

        int i32() throws IOException {
            int a = u16();
            return a | (u16() << 16);
        }

        void skip(long n) {
            while (n > 0 && hasMore()) {
                int take = (int) Math.min(n, cur.length - pos);
                pos += take;
                n -= take;
            }
        }

        String chars(int count, boolean wide) {
            StringBuilder sb = new StringBuilder(count);
            int remaining = count;
            while (remaining > 0) {
                if (pos >= cur.length) {
                    if (index + 1 >= chunks.size()) {
                        break;
                    }
                    cur = chunks.get(++index);
                    pos = 0;
                    if (cur.length == 0) {
                        continue;
                    }
                    wide = (cur[pos++] & 1) != 0; // flag byte of the continued characters
                    continue;
                }
                int per = wide ? 2 : 1;
                int n = Math.min(remaining, (cur.length - pos) / per);
                if (n == 0) {
                    pos = cur.length; // stray odd byte
                    continue;
                }
                for (int k = 0; k < n; k++) {
                    if (wide) {
                        sb.append((char) ((cur[pos] & 0xFF) | ((cur[pos + 1] & 0xFF) << 8)));
                        pos += 2;
                    } else {
                        sb.append((char) (cur[pos++] & 0xFF));
                    }
                }
                remaining -= n;
            }
            return sb.toString();
        }
    }

    private static final class BoundSheet {
        String name;
        int offset;
        int state;
        int type;
    }

    private static final class Biff {
        private final byte[] b;
        private final ArrayList<BoundSheet> sheets = new ArrayList<>();
        private final ArrayList<String> sst = new ArrayList<>();
        private final ArrayList<Integer> xf = new ArrayList<>();
        private final HashMap<Integer, String> customFormats = new HashMap<>();
        private final HashMap<Integer, ParadoksDocParsers.NumFmt> formatCache = new HashMap<>();
        private boolean date1904;

        Biff(byte[] b) {
            this.b = b;
        }

        ArrayList<ParadoksDocParsers.Sheet> parse() throws IOException {
            readGlobals();
            ArrayList<ParadoksDocParsers.Sheet> visible = new ArrayList<>();
            ArrayList<ParadoksDocParsers.Sheet> hidden = new ArrayList<>();
            for (BoundSheet bs : sheets) {
                if (bs.type != 0) {
                    continue; // charts and macro sheets
                }
                ParadoksDocParsers.Sheet sheet = new ParadoksDocParsers.Sheet();
                sheet.name = bs.name;
                readSheet(bs.offset, sheet);
                (bs.state == 0 ? visible : hidden).add(sheet);
            }
            ArrayList<ParadoksDocParsers.Sheet> result = visible.isEmpty() ? hidden : visible;
            if (result.isEmpty()) {
                throw new ParadoksDocParsers.ParseException("Jadvallar topilmadi");
            }
            return result;
        }

        private void readGlobals() throws IOException {
            if (b.length < 8 || u16(b, 0) != 0x0809) {
                throw new ParadoksDocParsers.ParseException("Bu Excel (.xls) fayli emas yoki buzilgan");
            }
            int version = u16(b, 4);
            if (version != 0x0600) {
                throw new ParadoksDocParsers.ParseException(version == 0x0500
                        ? "Juda eski Excel formati (5.0/95), qo'llab-quvvatlanmaydi"
                        : "Excel fayl versiyasi qo'llab-quvvatlanmaydi");
            }
            int pos = 0;
            int depth = 0;
            while (pos + 4 <= b.length) {
                int id = u16(b, pos);
                int len = u16(b, pos + 2);
                int start = pos + 4;
                if (start + len > b.length) {
                    break;
                }
                pos = start + len;
                if (id == 0x0809) {
                    depth++;
                } else if (id == 0x000A) {
                    depth--;
                    if (depth <= 0) {
                        break;
                    }
                } else if (id == 0x002F) {
                    throw new ParadoksDocParsers.ParseException("Fayl parol bilan himoyalangan");
                } else if (id == 0x0085 && len >= 8) {
                    BoundSheet bs = new BoundSheet();
                    bs.offset = i32(b, start);
                    bs.state = u8(b, start + 4) & 3;
                    bs.type = u8(b, start + 5);
                    bs.name = shortString(start + 6, start + len);
                    sheets.add(bs);
                } else if (id == 0x00FC) {
                    ArrayList<byte[]> chunks = new ArrayList<>();
                    chunks.add(java.util.Arrays.copyOfRange(b, start, start + len));
                    while (pos + 4 <= b.length && u16(b, pos) == 0x003C) {
                        int clen = u16(b, pos + 2);
                        if (pos + 4 + clen > b.length) {
                            break;
                        }
                        chunks.add(java.util.Arrays.copyOfRange(b, pos + 4, pos + 4 + clen));
                        pos += 4 + clen;
                    }
                    readSst(chunks);
                } else if (id == 0x00E0 && len >= 4) {
                    xf.add(u16(b, start + 2));
                } else if (id == 0x041E && len >= 5) {
                    customFormats.put(u16(b, start), longString(start + 2, start + len));
                } else if (id == 0x0022 && len >= 2) {
                    date1904 = u16(b, start) == 1;
                }
            }
        }

        private void readSst(ArrayList<byte[]> chunks) {
            try {
                ChunkReader r = new ChunkReader(chunks);
                r.i32(); // total references
                int unique = r.i32();
                for (int i = 0; i < unique && i < 2_000_000 && r.hasMore(); i++) {
                    int count = r.u16();
                    int flags = r.u8();
                    int runs = (flags & 8) != 0 ? r.u16() : 0;
                    int ext = (flags & 4) != 0 ? r.i32() : 0;
                    String s = r.chars(count, (flags & 1) != 0);
                    r.skip(runs * 4L + (ext & 0xFFFFFFFFL));
                    sst.add(s);
                }
            } catch (IOException ignore) {
                // keep what was read
            }
        }

        /** String with a 1-byte length (sheet names). */
        private String shortString(int at, int limit) {
            if (at + 2 > limit) {
                return "";
            }
            int count = u8(b, at);
            return decode(at + 2, limit, count, (u8(b, at + 1) & 1) != 0);
        }

        /** String with a 2-byte length (LABEL, STRING, FORMAT). */
        private String longString(int at, int limit) {
            if (at + 3 > limit) {
                return "";
            }
            int count = u16(b, at);
            int flags = u8(b, at + 2);
            int off = at + 3;
            if ((flags & 8) != 0) {
                off += 2;
            }
            if ((flags & 4) != 0) {
                off += 4;
            }
            return decode(off, limit, count, (flags & 1) != 0);
        }

        private String decode(int off, int limit, int count, boolean wide) {
            int bytes = Math.min(count * (wide ? 2 : 1), Math.max(0, limit - off));
            if (bytes <= 0) {
                return "";
            }
            try {
                return new String(b, off, bytes, wide ? "UTF-16LE" : "ISO-8859-1");
            } catch (java.io.UnsupportedEncodingException e) {
                return "";
            }
        }

        private ParadoksDocParsers.NumFmt format(int xfIndex) {
            int id = xfIndex >= 0 && xfIndex < xf.size() ? xf.get(xfIndex) : 0;
            ParadoksDocParsers.NumFmt f = formatCache.get(id);
            if (f == null) {
                f = ParadoksDocParsers.NumFmt.of(id, customFormats.get(id));
                formatCache.put(id, f);
            }
            return f;
        }

        private String number(double v, int xfIndex) {
            return format(xfIndex).format(v, date1904);
        }

        private static double rk(int rk) {
            double value;
            if ((rk & 2) != 0) {
                value = rk >> 2;
            } else {
                value = Double.longBitsToDouble((long) (rk & 0xFFFFFFFC) << 32);
            }
            return (rk & 1) != 0 ? value / 100 : value;
        }

        private static String errorText(int code) {
            switch (code) {
                case 0x00:
                    return "#NULL!";
                case 0x07:
                    return "#DIV/0!";
                case 0x0F:
                    return "#VALUE!";
                case 0x17:
                    return "#REF!";
                case 0x1D:
                    return "#NAME?";
                case 0x24:
                    return "#NUM!";
                case 0x2A:
                    return "#N/A";
                default:
                    return "#ERR";
            }
        }

        private void readSheet(int offset, ParadoksDocParsers.Sheet sheet) {
            ArrayList<ArrayList<String>> grid = new ArrayList<>();
            int pos = offset;
            int depth = 0;
            int pendingRow = -1;
            int pendingCol = -1;
            while (pos >= 0 && pos + 4 <= b.length) {
                int id = u16(b, pos);
                int len = u16(b, pos + 2);
                int s = pos + 4;
                if (s + len > b.length) {
                    break;
                }
                pos = s + len;
                switch (id) {
                    case 0x0809:
                        depth++;
                        break;
                    case 0x000A:
                        depth--;
                        break;
                    case 0x00FD: // LABELSST
                        if (len >= 10) {
                            int idx = i32(b, s + 6);
                            put(grid, sheet, u16(b, s), u16(b, s + 2), idx >= 0 && idx < sst.size() ? sst.get(idx) : null);
                        }
                        break;
                    case 0x0203: // NUMBER
                        if (len >= 14) {
                            put(grid, sheet, u16(b, s), u16(b, s + 2), number(Double.longBitsToDouble(i64(b, s + 6)), u16(b, s + 4)));
                        }
                        break;
                    case 0x027E: // RK
                        if (len >= 10) {
                            put(grid, sheet, u16(b, s), u16(b, s + 2), number(rk(i32(b, s + 6)), u16(b, s + 4)));
                        }
                        break;
                    case 0x00BD: // MULRK
                        if (len >= 6) {
                            int row = u16(b, s);
                            int first = u16(b, s + 2);
                            int count = (len - 6) / 6;
                            for (int i = 0; i < count; i++) {
                                int at = s + 4 + i * 6;
                                put(grid, sheet, row, first + i, number(rk(i32(b, at + 2)), u16(b, at)));
                            }
                        }
                        break;
                    case 0x0204: // LABEL
                    case 0x00D6: // RSTRING
                        if (len >= 9) {
                            put(grid, sheet, u16(b, s), u16(b, s + 2), longString(s + 6, s + len));
                        }
                        break;
                    case 0x0205: // BOOLERR
                        if (len >= 8) {
                            int value = u8(b, s + 6);
                            put(grid, sheet, u16(b, s), u16(b, s + 2), u8(b, s + 7) != 0 ? errorText(value) : (value != 0 ? "TRUE" : "FALSE"));
                        }
                        break;
                    case 0x0006: // FORMULA: the stored result
                        if (len >= 14) {
                            int row = u16(b, s);
                            int col = u16(b, s + 2);
                            pendingRow = -1;
                            if (u16(b, s + 12) == 0xFFFF) {
                                int kind = u8(b, s + 6);
                                if (kind == 0) {
                                    pendingRow = row;
                                    pendingCol = col;
                                } else if (kind == 1) {
                                    put(grid, sheet, row, col, u8(b, s + 8) != 0 ? "TRUE" : "FALSE");
                                } else if (kind == 2) {
                                    put(grid, sheet, row, col, errorText(u8(b, s + 8)));
                                }
                            } else {
                                put(grid, sheet, row, col, number(Double.longBitsToDouble(i64(b, s + 6)), u16(b, s + 4)));
                            }
                        }
                        break;
                    case 0x0207: // STRING: text result of the formula just before
                        if (pendingRow >= 0 && len >= 3) {
                            put(grid, sheet, pendingRow, pendingCol, longString(s, s + len));
                        }
                        pendingRow = -1;
                        break;
                    default:
                        break;
                }
                if (depth <= 0 && id == 0x000A) {
                    break;
                }
            }
            for (ArrayList<String> row : grid) {
                if (row == null) {
                    sheet.rows.add(new String[0]);
                    continue;
                }
                int last = row.size() - 1;
                while (last >= 0 && row.get(last) == null) {
                    last--;
                }
                String[] cells = new String[last + 1];
                for (int i = 0; i <= last; i++) {
                    cells[i] = row.get(i);
                }
                sheet.rows.add(cells);
                sheet.cols = Math.max(sheet.cols, cells.length);
            }
        }

        private static void put(ArrayList<ArrayList<String>> grid, ParadoksDocParsers.Sheet sheet, int row, int col, String value) {
            if (value == null || value.isEmpty()) {
                return;
            }
            if (row >= ParadoksDocParsers.MAX_ROWS) {
                sheet.truncatedRows = true;
                return;
            }
            if (col >= ParadoksDocParsers.MAX_COLS) {
                sheet.truncatedCols = true;
                return;
            }
            while (grid.size() <= row) {
                grid.add(null);
            }
            ArrayList<String> cells = grid.get(row);
            if (cells == null) {
                cells = new ArrayList<>();
                grid.set(row, cells);
            }
            while (cells.size() <= col) {
                cells.add(null);
            }
            cells.set(col, value);
        }
    }

    private ParadoksXlsReader() {
    }
}
