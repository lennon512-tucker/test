import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Builds WAL logs for tests. Only encodes: it never decodes or interprets a log.
 * Methods return this builder; build() returns a fresh copy of the bytes.
 */
public final class LogBuilder {
    private static final byte[] MAGIC = {0x57, 0x41, 0x4C, 0x31};

    private byte[] buf = new byte[64];
    private int len = 0;
    /** End offset of the last record written by record() or frame(); -1 if none. */
    private int lastRecordEnd = -1;

    private LogBuilder() {}

    /** A log that starts with the magic. */
    public static LogBuilder wal() {
        LogBuilder b = new LogBuilder();
        b.append(MAGIC);
        return b;
    }

    /** A log that starts empty. */
    public static LogBuilder raw() {
        return new LogBuilder();
    }

    // ---- correct records ----

    public LogBuilder begin(long txn) { return record(1, u32(txn)); }

    public LogBuilder put(long txn, String key, String value) {
        byte[] k = latin1(key), v = latin1(value);
        if (k.length > 255) throw new IllegalArgumentException("key longer than 255 bytes");
        if (v.length > 65535) throw new IllegalArgumentException("value longer than 65535 bytes");
        byte[] p = new byte[4 + 1 + k.length + 2 + v.length];
        int i = 0;
        System.arraycopy(u32(txn), 0, p, i, 4); i += 4;
        p[i++] = (byte) k.length;
        System.arraycopy(k, 0, p, i, k.length); i += k.length;
        p[i++] = (byte) (v.length >> 8);
        p[i++] = (byte) v.length;
        System.arraycopy(v, 0, p, i, v.length);
        return record(2, p);
    }

    public LogBuilder del(long txn, String key) {
        byte[] k = latin1(key);
        if (k.length > 255) throw new IllegalArgumentException("key longer than 255 bytes");
        byte[] p = new byte[4 + 1 + k.length];
        System.arraycopy(u32(txn), 0, p, 0, 4);
        p[4] = (byte) k.length;
        System.arraycopy(k, 0, p, 5, k.length);
        return record(3, p);
    }

    public LogBuilder commit(long txn) { return record(4, u32(txn)); }

    public LogBuilder abort(long txn) { return record(5, u32(txn)); }

    /** SAVEPOINT (type 6): txn (4 bytes), name (1 byte, any value 0 to 255). */
    public LogBuilder savepoint(long txn, int name) { return record(6, txnAndName(txn, name)); }

    /** ROLLBACK (type 7): txn (4 bytes), name (1 byte, any value 0 to 255). */
    public LogBuilder rollback(long txn, int name) { return record(7, txnAndName(txn, name)); }

    /**
     * CHECKPOINT (type 8): c (1 byte) = ids.length, then each id as 4 bytes. Ids are written as
     * given (any order, repeats and 0 allowed), so tests can build wrong lists.
     */
    public LogBuilder checkpoint(long... ids) {
        if (ids.length > 255) throw new IllegalArgumentException("more than 255 ids");
        byte[] p = new byte[1 + 4 * ids.length];
        p[0] = (byte) ids.length;
        for (int i = 0; i < ids.length; i++) System.arraycopy(u32(ids[i]), 0, p, 1 + 4 * i, 4);
        return record(8, p);
    }

    /** A record with the true payload length and the correct checksum. */
    public LogBuilder record(int type, byte[] payload) {
        if (payload.length > 65535) throw new IllegalArgumentException("payload longer than 65535 bytes");
        byte[] covered = new byte[3 + payload.length];
        covered[0] = (byte) type;
        covered[1] = (byte) (payload.length >> 8);
        covered[2] = (byte) payload.length;
        System.arraycopy(payload, 0, covered, 3, payload.length);
        return frame(type, payload.length, payload, fletcher16(covered, 0, covered.length));
    }

    // ---- anything, for corrupt frames ----

    /**
     * Writes type (1 byte), declaredLength (2 bytes), the payload exactly as given, then the
     * checksum (2 bytes: high byte = s2, low byte = s1, so 0x1C06 is written as 1C 06).
     * declaredLength need not equal payload.length.
     */
    public LogBuilder frame(int type, int declaredLength, byte[] payload, int checksum) {
        if (type < 0 || type > 255) throw new IllegalArgumentException("type out of range");
        if (declaredLength < 0 || declaredLength > 65535) throw new IllegalArgumentException("length out of range");
        if (checksum < 0 || checksum > 65535) throw new IllegalArgumentException("checksum out of range");
        append(new byte[] {(byte) type, (byte) (declaredLength >> 8), (byte) declaredLength});
        append(payload);
        append(new byte[] {(byte) (checksum >> 8), (byte) checksum});
        lastRecordEnd = len;
        return this;
    }

    /** Raw bytes, each value taken as 0 to 255. */
    public LogBuilder bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] < 0 || values[i] > 255) throw new IllegalArgumentException("byte out of range: " + values[i]);
            b[i] = (byte) values[i];
        }
        append(b);
        return this;
    }

    // ---- edits ----

    /** XORs the stored checksum of the last record (written by record() or frame()) with 0x0101. */
    public LogBuilder corruptLastChecksum() {
        if (lastRecordEnd < 2 || lastRecordEnd > len) throw new IllegalStateException("no record to corrupt");
        buf[lastRecordEnd - 2] ^= 0x01;
        buf[lastRecordEnd - 1] ^= 0x01;
        return this;
    }

    /** Drops the last n bytes. */
    public LogBuilder truncate(int n) {
        if (n < 0 || n > len) throw new IllegalArgumentException("cannot drop " + n + " of " + len + " bytes");
        len -= n;
        if (lastRecordEnd > len) lastRecordEnd = -1;
        return this;
    }

    public int size() { return len; }

    /** A fresh copy of the bytes built so far. */
    public byte[] build() { return Arrays.copyOf(buf, len); }

    // ---- checksum ----

    /**
     * Fletcher-16 over data[from..to) (to is exclusive), each byte taken as 0 to 255.
     * Returns (s2 << 8) | s1, the stored order: s2 first, then s1.
     */
    public static int fletcher16(byte[] data, int from, int to) {
        int s1 = 0, s2 = 0;
        for (int i = from; i < to; i++) {
            s1 = (s1 + (data[i] & 0xFF)) % 255;
            s2 = (s2 + s1) % 255;
        }
        return (s2 << 8) | s1;
    }

    // ---- helpers ----

    private static byte[] u32(long v) {
        if (v < 0 || v > 4294967295L) throw new IllegalArgumentException("txn id out of u32 range: " + v);
        return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] txnAndName(long txn, int name) {
        if (name < 0 || name > 255) throw new IllegalArgumentException("name out of byte range: " + name);
        byte[] p = new byte[5];
        System.arraycopy(u32(txn), 0, p, 0, 4);
        p[4] = (byte) name;
        return p;
    }

    private static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private void append(byte[] b) {
        if (len + b.length > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + b.length));
        System.arraycopy(b, 0, buf, len, b.length);
        len += b.length;
    }
}
