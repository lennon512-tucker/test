import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WalRecovery {
    private WalRecovery() {}

    private static final byte[] MAGIC = {0x57, 0x41, 0x4C, 0x31};
    private static final int HEADER_SIZE = 4;
    private static final int MAX_PAYLOAD = 1024;
    private static final int MIN_KEY_LEN = 1;
    private static final int MAX_KEY_LEN = 32;
    private static final int KEY_BYTE_MIN = 0x21;
    private static final int KEY_BYTE_MAX = 0x7E;
    private static final int VALUE_BYTE_MIN = 0x20;
    private static final int VALUE_BYTE_MAX = 0x7E;
    private static final int MAX_OPEN_TXNS = 4;
    private static final int MAX_WRITES = 8;
    private static final int NAME_BYTE_MIN = 0x21;
    private static final int NAME_BYTE_MAX = 0x7E;

    private static final int TYPE_BEGIN = 1;
    private static final int TYPE_PUT = 2;
    private static final int TYPE_DELETE = 3;
    private static final int TYPE_COMMIT = 4;
    private static final int TYPE_ABORT = 5;
    private static final int TYPE_SAVEPOINT = 6;
    private static final int TYPE_ROLLBACK = 7;

    private record IntactRecord(int offset, int type, int payloadLength) {}

    private record Write(boolean isPut, String key, String value) {}

    private record Savepoint(int position, int settingRecordOffset) {}

    private static final class Transaction {
        final List<Write> writes = new ArrayList<>();
        final Map<Integer, Savepoint> savepoints = new HashMap<>();
    }

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        // R1: If log is null, throw a NullPointerException
        Objects.requireNonNull(log);

        final int L = log.length;

        // R2: If L = 0, return the empty result with validLength = 0 and tornTail = false.
        if (L == 0) {
            return emptyResult(0, false);
        }

        // R3: If L is 1, 2 or 3: check prefix against MAGIC
        if (L < HEADER_SIZE) {
            for (int i = 0; i < L; i++) {
                if (log[i] != MAGIC[i]) {
                    throw new WalException(Code.BAD_MAGIC, 0);
                }
            }
            return emptyResult(0, true);
        }

        // R4: If L >= 4 and bytes 0 to 3 are not MAGIC, throw BAD_MAGIC at 0.
        for (int i = 0; i < HEADER_SIZE; i++) {
            if (log[i] != MAGIC[i]) {
                throw new WalException(Code.BAD_MAGIC, 0);
            }
        }

        // Phase 1: frames
        List<IntactRecord> intactRecords = new ArrayList<>();
        int p = HEADER_SIZE;
        int validLength;
        boolean tornTail;

        while (true) {
            // R5: If p = L, phase 1 ends with no torn tail
            if (p == L) {
                validLength = L;
                tornTail = false;
                break;
            }

            // R6: If L - p is 1 or 2, there is a torn tail at p (R12)
            int rem = L - p;
            if (rem == 1 || rem == 2) {
                validLength = p;
                tornTail = true;
                break;
            }

            // R7: Type byte check (1 to 7)
            int type = u8(log, p);
            if (type < TYPE_BEGIN || type > TYPE_ROLLBACK) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }

            // R8: Payload length check
            int N = u16(log, p + 1);
            if (N > MAX_PAYLOAD) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }

            // R9: Record fit check
            int nextP = p + 5 + N;
            if (nextP > L) {
                validLength = p;
                tornTail = true;
                break;
            }

            // R10: Checksum check (Fletcher-16 over p to p + 2 + N)
            int s1 = 0;
            int s2 = 0;
            int endChecksumData = p + 3 + N;
            for (int i = p; i < endChecksumData; i++) {
                s1 = (s1 + (log[i] & 0xFF)) % 255;
                s2 = (s2 + s1) % 255;
            }

            int storedS2 = u8(log, p + 3 + N);
            int storedS1 = u8(log, p + 4 + N);

            if (storedS2 != s2 || storedS1 != s1) {
                if (nextP == L) {
                    validLength = p;
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }

            // R11: Intact record
            intactRecords.add(new IntactRecord(p, type, N));
            p = nextP;
        }

        // Phase 2: replay
        Map<String, String> state = new LinkedHashMap<>();
        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();
        Map<Long, Transaction> openTxns = new HashMap<>();
        Set<Long> seenTxns = new HashSet<>();

        for (IntactRecord rec : intactRecords) {
            int offset = rec.offset();
            int type = rec.type();
            int N = rec.payloadLength();
            int payloadBase = offset + 3;

            int k = 0;
            int v = 0;
            int name = 0;

            // R13: Payload layout
            switch (type) {
                case TYPE_BEGIN, TYPE_COMMIT, TYPE_ABORT -> {
                    if (N != 4) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
                case TYPE_PUT -> {
                    if (N < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    k = u8(log, payloadBase + 4);
                    if (N < 7 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    v = u16(log, payloadBase + 5 + k);
                    if (N != 7 + k + v) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
                case TYPE_DELETE -> {
                    if (N < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    k = u8(log, payloadBase + 4);
                    if (N != 5 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
                case TYPE_SAVEPOINT, TYPE_ROLLBACK -> {
                    if (N != 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    name = u8(log, payloadBase + 4);
                }
            }

            // R14: Txn id
            long txn = u32(log, payloadBase);
            if (txn == 0L) {
                throw new WalException(Code.BAD_TXN_ID, offset);
            }

            // R15: Key (PUT, DELETE)
            if (type == TYPE_PUT || type == TYPE_DELETE) {
                if (k < MIN_KEY_LEN || k > MAX_KEY_LEN) {
                    throw new WalException(Code.BAD_KEY, offset);
                }
                int keyStart = payloadBase + 5;
                for (int i = 0; i < k; i++) {
                    int b = u8(log, keyStart + i);
                    if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                        throw new WalException(Code.BAD_KEY, offset);
                    }
                }
            }

            // R16: Value (PUT)
            if (type == TYPE_PUT) {
                int valStart = payloadBase + 7 + k;
                for (int i = 0; i < v; i++) {
                    int b = u8(log, valStart + i);
                    if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                        throw new WalException(Code.BAD_VALUE, offset);
                    }
                }
            }

            // R16a: Name (SAVEPOINT, ROLLBACK)
            if (type == TYPE_SAVEPOINT || type == TYPE_ROLLBACK) {
                if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) {
                    throw new WalException(Code.BAD_NAME, offset);
                }
            }

            // R17 & R18: BEGIN transaction checks
            if (type == TYPE_BEGIN) {
                if (seenTxns.contains(txn)) {
                    throw new WalException(Code.DUPLICATE_TXN, offset);
                }
                if (openTxns.size() >= MAX_OPEN_TXNS) {
                    throw new WalException(Code.TOO_MANY_OPEN, offset);
                }
            } else {
                // R19: Open check (PUT, DELETE, COMMIT, ABORT, SAVEPOINT, ROLLBACK)
                Transaction t = openTxns.get(txn);
                if (t == null) {
                    throw new WalException(Code.TXN_NOT_OPEN, offset);
                }

                // R19a: Write limit (PUT, DELETE)
                if (type == TYPE_PUT || type == TYPE_DELETE) {
                    if (t.writes.size() >= MAX_WRITES) {
                        throw new WalException(Code.TXN_TOO_LARGE, offset);
                    }
                }

                // R19b: Savepoint check (ROLLBACK)
                if (type == TYPE_ROLLBACK) {
                    if (!t.savepoints.containsKey(name)) {
                        throw new WalException(Code.NO_SAVEPOINT, offset);
                    }
                }
            }

            // Actions (R20 to R23b)
            switch (type) {
                case TYPE_BEGIN -> {
                    // R20
                    seenTxns.add(txn);
                    openTxns.put(txn, new Transaction());
                }
                case TYPE_PUT -> {
                    // R21
                    String keyStr = asciiString(log, payloadBase + 5, k);
                    String valStr = asciiString(log, payloadBase + 7 + k, v);
                    openTxns.get(txn).writes.add(new Write(true, keyStr, valStr));
                }
                case TYPE_DELETE -> {
                    // R21
                    String keyStr = asciiString(log, payloadBase + 5, k);
                    openTxns.get(txn).writes.add(new Write(false, keyStr, null));
                }
                case TYPE_COMMIT -> {
                    // R22
                    Transaction t = openTxns.remove(txn);
                    for (Write w : t.writes) {
                        if (w.isPut()) {
                            state.put(w.key(), w.value());
                        } else {
                            state.remove(w.key());
                        }
                    }
                    committed.add(txn);
                }
                case TYPE_ABORT -> {
                    // R23
                    openTxns.remove(txn);
                    aborted.add(txn);
                }
                case TYPE_SAVEPOINT -> {
                    // R23a
                    Transaction t = openTxns.get(txn);
                    t.savepoints.put(name, new Savepoint(t.writes.size(), offset));
                }
                case TYPE_ROLLBACK -> {
                    // R23b
                    Transaction t = openTxns.get(txn);
                    Savepoint s = t.savepoints.get(name);
                    int n = s.position();
                    if (t.writes.size() > n) {
                        t.writes.subList(n, t.writes.size()).clear();
                    }
                    int sOffset = s.settingRecordOffset();
                    Iterator<Map.Entry<Integer, Savepoint>> it = t.savepoints.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<Integer, Savepoint> entry = it.next();
                        if (entry.getValue().settingRecordOffset() > sOffset) {
                            it.remove();
                        }
                    }
                }
            }
        }

        // R26: incomplete transactions in ascending numeric order
        List<Long> incomplete = new ArrayList<>(openTxns.keySet());
        Collections.sort(incomplete);

        return new Result(
                Collections.unmodifiableMap(state),
                List.copyOf(committed),
                List.copyOf(aborted),
                List.copyOf(incomplete),
                validLength,
                tornTail
        );
    }

    private static Result emptyResult(int validLength, boolean tornTail) {
        return new Result(Map.of(), List.of(), List.of(), List.of(), validLength, tornTail);
    }

    private static int u8(byte[] log, int pos) {
        return log[pos] & 0xFF;
    }

    private static int u16(byte[] log, int pos) {
        return ((log[pos] & 0xFF) << 8) | (log[pos + 1] & 0xFF);
    }

    private static long u32(byte[] log, int pos) {
        return ((long) (log[pos] & 0xFF) << 24)
                | ((long) (log[pos + 1] & 0xFF) << 16)
                | ((long) (log[pos + 2] & 0xFF) << 8)
                | ((long) (log[pos + 3] & 0xFF));
    }

    private static String asciiString(byte[] log, int start, int len) {
        if (len == 0) {
            return "";
        }
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            chars[i] = (char) (log[start + i] & 0xFF);
        }
        return new String(chars);
    }

    public record Result(Map<String, String> state,
                         List<Long> committed,
                         List<Long> aborted,
                         List<Long> incomplete,
                         int validLength,
                         boolean tornTail) {}

    public enum Code {
        BAD_MAGIC, UNKNOWN_TYPE, RECORD_TOO_LARGE, CHECKSUM_MISMATCH,
        MALFORMED_PAYLOAD, BAD_TXN_ID, BAD_KEY, BAD_VALUE,
        DUPLICATE_TXN, TOO_MANY_OPEN, TXN_NOT_OPEN,
        TXN_TOO_LARGE, BAD_NAME, NO_SAVEPOINT
    }

    public static final class WalException extends Exception {
        private final Code code;
        private final int offset;
        public WalException(Code code, int offset) {
            super(code + "@" + offset);
            this.code = code;
            this.offset = offset;
        }
        public Code code() { return code; }
        public int offset() { return offset; }
    }
}
