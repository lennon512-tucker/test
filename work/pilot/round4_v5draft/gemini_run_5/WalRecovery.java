import java.nio.charset.StandardCharsets;
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
import java.util.TreeMap;

public final class WalRecovery {
    private static final byte[] MAGIC = {0x57, 0x41, 0x4C, 0x31};
    private static final int HEADER_SIZE = 4;
    private static final int MAX_PAYLOAD = 1024;
    private static final int MIN_KEY_LEN = 1;
    private static final int MAX_KEY_LEN = 32;
    private static final int KEY_BYTE_MIN = 0x21;
    private static final int KEY_BYTE_MAX = 0x7E;
    private static final int VALUE_BYTE_MIN = 0x20;
    private static final int VALUE_BYTE_MAX = 0x7E;
    private static final int NAME_BYTE_MIN = 0x21;
    private static final int NAME_BYTE_MAX = 0x7E;
    private static final int MAX_OPEN_TXNS = 4;
    private static final int MAX_WRITES = 8;

    private static final int TYPE_BEGIN = 1;
    private static final int TYPE_PUT = 2;
    private static final int TYPE_DELETE = 3;
    private static final int TYPE_COMMIT = 4;
    private static final int TYPE_ABORT = 5;
    private static final int TYPE_SAVEPOINT = 6;
    private static final int TYPE_ROLLBACK = 7;
    private static final int TYPE_CHECKPOINT = 8;
    private static final int TYPE_READ = 9;

    private WalRecovery() {}

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        Objects.requireNonNull(log);
        final int length = log.length;

        // R2: Empty log
        if (length == 0) {
            return emptyResult(0, false);
        }

        // R3: Truncated header (1..3 bytes)
        if (length < HEADER_SIZE) {
            for (int i = 0; i < length; i++) {
                if (log[i] != MAGIC[i]) {
                    throw new WalException(Code.BAD_MAGIC, 0);
                }
            }
            return emptyResult(0, true);
        }

        // R4: Full header check
        for (int i = 0; i < HEADER_SIZE; i++) {
            if (log[i] != MAGIC[i]) {
                throw new WalException(Code.BAD_MAGIC, 0);
            }
        }

        // Phase 1: Frame validation
        List<Frame> intactRecords = new ArrayList<>();
        int p = HEADER_SIZE;
        int validLength;
        boolean tornTail;

        while (true) {
            // R5: Exact end of log
            if (p == length) {
                validLength = length;
                tornTail = false;
                break;
            }

            // R6: Fewer than 3 bytes left
            int remaining = length - p;
            if (remaining == 1 || remaining == 2) {
                validLength = p;
                tornTail = true;
                break;
            }

            // R7: Record type check
            int type = u8(log, p);
            if (type < TYPE_BEGIN || type > TYPE_READ) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }

            // R8: Payload length check
            int n = u16(log, p + 1);
            if (n > MAX_PAYLOAD) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }

            // R9: Record fit check
            long recordEnd = (long) p + 5L + n;
            if (recordEnd > length) {
                validLength = p;
                tornTail = true;
                break;
            }

            // R10: Fletcher-16 checksum check
            int endOffset = (int) recordEnd;
            if (!verifyChecksum(log, p, n)) {
                if (endOffset == length) {
                    validLength = p;
                    tornTail = true;
                    break;
                }
                throw new WalException(Code.CHECKSUM_MISMATCH, p);
            }

            // R11: Intact record
            intactRecords.add(new Frame(p, type, n));
            p = endOffset;
        }

        // Phase 2: Replay intact records
        Map<String, String> state = new LinkedHashMap<>();
        TreeMap<Long, Txn> openTxns = new TreeMap<>();
        Set<Long> seenBeginTxns = new HashSet<>();
        List<CommittedTxn> committedHistory = new ArrayList<>();
        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();

        for (Frame frame : intactRecords) {
            replayRecord(
                    log,
                    frame,
                    state,
                    openTxns,
                    seenBeginTxns,
                    committedHistory,
                    committed,
                    aborted);
        }

        List<Long> incomplete = List.copyOf(openTxns.keySet());
        return new Result(
                Collections.unmodifiableMap(state),
                Collections.unmodifiableList(committed),
                Collections.unmodifiableList(aborted),
                incomplete,
                validLength,
                tornTail);
    }

    private static void replayRecord(
            byte[] log,
            Frame frame,
            Map<String, String> state,
            TreeMap<Long, Txn> openTxns,
            Set<Long> seenBeginTxns,
            List<CommittedTxn> committedHistory,
            List<Long> committed,
            List<Long> aborted) throws WalException {
        final int p = frame.offset;
        final int type = frame.type;
        final int n = frame.payloadLen;
        final int pay = p + 3;

        switch (type) {
            case TYPE_BEGIN -> {
                // R13
                if (n != 4) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                // R14
                long txn = u32(log, pay);
                if (txn == 0) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
                // R17
                if (seenBeginTxns.contains(txn)) {
                    throw new WalException(Code.DUPLICATE_TXN, p);
                }
                // R18
                if (openTxns.size() >= MAX_OPEN_TXNS) {
                    throw new WalException(Code.TOO_MANY_OPEN, p);
                }
                // R20
                seenBeginTxns.add(txn);
                openTxns.put(txn, new Txn(txn, p, new HashMap<>(state)));
            }

            case TYPE_PUT -> {
                // R13
                if (n < 5) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                int k = u8(log, pay + 4);
                if (n < 7 + k) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                int v = u16(log, pay + 5 + k);
                if (n != 7 + k + v) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                // R14
                long txn = u32(log, pay);
                if (txn == 0) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
                // R15
                checkKey(log, pay + 5, k, p);
                // R16
                checkValue(log, pay + 7 + k, v, p);
                // R19
                Txn t = requireOpenTxn(openTxns, txn, p);
                // R19a
                if (t.writes.size() >= MAX_WRITES) {
                    throw new WalException(Code.TXN_TOO_LARGE, p);
                }
                // R21
                String key = decodeAscii(log, pay + 5, k);
                String value = decodeAscii(log, pay + 7 + k, v);
                t.writes.add(new Write(false, key, value));
            }

            case TYPE_DELETE -> {
                // R13
                if (n < 5) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                int k = u8(log, pay + 4);
                if (n != 5 + k) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                // R14
                long txn = u32(log, pay);
                if (txn == 0) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
                // R15
                checkKey(log, pay + 5, k, p);
                // R19
                Txn t = requireOpenTxn(openTxns, txn, p);
                // R19a
                if (t.writes.size() >= MAX_WRITES) {
                    throw new WalException(Code.TXN_TOO_LARGE, p);
                }
                // R21
                String key = decodeAscii(log, pay + 5, k);
                t.writes.add(new Write(true, key, null));
            }

            case TYPE_COMMIT -> {
                // R13
                if (n != 4) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                // R14
                long txn = u32(log, pay);
                if (txn == 0) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
                // R19
                Txn t = requireOpenTxn(openTxns, txn, p);
                // R22
                boolean conflict = false;
                if (!t.readSet.isEmpty()) {
                    for (CommittedTxn u : committedHistory) {
                        if (u.commitOffset > t.beginOffset
                                && !Collections.disjoint(u.writtenKeys, t.readSet)) {
                            conflict = true;
                            break;
                        }
                    }
                }
                openTxns.remove(txn);
                if (conflict) {
                    aborted.add(txn);
                } else {
                    Set<String> writtenKeys = new HashSet<>();
                    for (Write w : t.writes) {
                        writtenKeys.add(w.key);
                        if (w.isDelete) {
                            state.remove(w.key);
                        } else {
                            state.put(w.key, w.value);
                        }
                    }
                    committedHistory.add(new CommittedTxn(p, writtenKeys));
                    committed.add(txn);
                }
            }

            case TYPE_ABORT -> {
                // R13
                if (n != 4) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                // R14
                long txn = u32(log, pay);
                if (txn == 0) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
                // R19
                requireOpenTxn(openTxns, txn, p);
                // R23
                openTxns.remove(txn);
                aborted.add(txn);
            }

            case TYPE_SAVEPOINT -> {
                // R13
                if (n != 5) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                // R14
                long txn = u32(log, pay);
                if (txn == 0) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
                // R16a
                int name = u8(log, pay + 4);
                checkName(name, p);
                // R19
                Txn t = requireOpenTxn(openTxns, txn, p);
                // R23a
                t.savepoints.put(name, new Savepoint(t.writes.size(), p));
            }

            case TYPE_ROLLBACK -> {
                // R13
                if (n != 5) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                // R14
                long txn = u32(log, pay);
                if (txn == 0) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
                // R16a
                int name = u8(log, pay + 4);
                checkName(name, p);
                // R19
                Txn t = requireOpenTxn(openTxns, txn, p);
                // R19b
                Savepoint target = t.savepoints.get(name);
                if (target == null) {
                    throw new WalException(Code.NO_SAVEPOINT, p);
                }
                // R23b
                t.writes.subList(target.position, t.writes.size()).clear();
                t.savepoints.values().removeIf(sp -> sp.setOffset > target.setOffset);
            }

            case TYPE_CHECKPOINT -> {
                // R13
                if (n < 1) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                int c = u8(log, pay);
                if (n != 1 + 4 * c) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                // R14
                long[] listed = new long[c];
                for (int i = 0; i < c; i++) {
                    long id = u32(log, pay + 1 + 4 * i);
                    if (id == 0) {
                        throw new WalException(Code.BAD_TXN_ID, p);
                    }
                    listed[i] = id;
                }
                // R19c
                if (c != openTxns.size()) {
                    throw new WalException(Code.CHECKPOINT_MISMATCH, p);
                }
                Iterator<Long> it = openTxns.keySet().iterator();
                for (int i = 0; i < c; i++) {
                    if (listed[i] != it.next()) {
                        throw new WalException(Code.CHECKPOINT_MISMATCH, p);
                    }
                }
                // R23c: no state change
            }

            case TYPE_READ -> {
                // R13
                if (n < 6) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                int k = u8(log, pay + 4);
                if (n < 6 + k) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                int f = u8(log, pay + 5 + k);
                int v = 0;
                if (f == 0) {
                    if (n != 6 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                } else if (f == 1) {
                    if (n < 8 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                    v = u16(log, pay + 6 + k);
                    if (n != 8 + k + v) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                } else {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                // R14
                long txn = u32(log, pay);
                if (txn == 0) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
                // R15
                checkKey(log, pay + 5, k, p);
                // R16
                if (f == 1) {
                    checkValue(log, pay + 8 + k, v, p);
                }
                // R19
                Txn t = requireOpenTxn(openTxns, txn, p);
                // R19d
                String key = decodeAscii(log, pay + 5, k);
                boolean viewPresent = false;
                String viewValue = null;
                boolean foundInWrites = false;
                for (int i = t.writes.size() - 1; i >= 0; i--) {
                    Write w = t.writes.get(i);
                    if (w.key.equals(key)) {
                        foundInWrites = true;
                        viewPresent = !w.isDelete;
                        viewValue = w.value;
                        break;
                    }
                }
                if (!foundInWrites && t.snapshot.containsKey(key)) {
                    viewPresent = true;
                    viewValue = t.snapshot.get(key);
                }

                if (f == 0) {
                    if (viewPresent) {
                        throw new WalException(Code.READ_MISMATCH, p);
                    }
                } else {
                    String expectedValue = decodeAscii(log, pay + 8 + k, v);
                    if (!viewPresent || !viewValue.equals(expectedValue)) {
                        throw new WalException(Code.READ_MISMATCH, p);
                    }
                }
                // R23d
                t.readSet.add(key);
            }

            default -> throw new WalException(Code.UNKNOWN_TYPE, p);
        }
    }

    private static Txn requireOpenTxn(TreeMap<Long, Txn> openTxns, long txn, int p)
            throws WalException {
        Txn t = openTxns.get(txn);
        if (t == null) {
            throw new WalException(Code.TXN_NOT_OPEN, p);
        }
        return t;
    }

    private static void checkKey(byte[] log, int offset, int len, int p) throws WalException {
        if (len < MIN_KEY_LEN || len > MAX_KEY_LEN) {
            throw new WalException(Code.BAD_KEY, p);
        }
        for (int i = 0; i < len; i++) {
            int b = u8(log, offset + i);
            if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                throw new WalException(Code.BAD_KEY, p);
            }
        }
    }

    private static void checkValue(byte[] log, int offset, int len, int p) throws WalException {
        for (int i = 0; i < len; i++) {
            int b = u8(log, offset + i);
            if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                throw new WalException(Code.BAD_VALUE, p);
            }
        }
    }

    private static void checkName(int name, int p) throws WalException {
        if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) {
            throw new WalException(Code.BAD_NAME, p);
        }
    }

    private static boolean verifyChecksum(byte[] log, int p, int n) {
        int s1 = 0;
        int s2 = 0;
        int limit = p + 3 + n;
        for (int i = p; i < limit; i++) {
            s1 = (s1 + (log[i] & 0xFF)) % 255;
            s2 = (s2 + s1) % 255;
        }
        return u8(log, p + 3 + n) == s2 && u8(log, p + 4 + n) == s1;
    }

    private static String decodeAscii(byte[] log, int offset, int len) {
        if (len == 0) {
            return "";
        }
        return new String(log, offset, len, StandardCharsets.ISO_8859_1);
    }

    private static int u8(byte[] log, int offset) {
        return log[offset] & 0xFF;
    }

    private static int u16(byte[] log, int offset) {
        return ((log[offset] & 0xFF) << 8) | (log[offset + 1] & 0xFF);
    }

    private static long u32(byte[] log, int offset) {
        return (((long) (log[offset] & 0xFF)) << 24)
                | (((long) (log[offset + 1] & 0xFF)) << 16)
                | (((long) (log[offset + 2] & 0xFF)) << 8)
                | ((long) (log[offset + 3] & 0xFF));
    }

    private static Result emptyResult(int validLength, boolean tornTail) {
        return new Result(Map.of(), List.of(), List.of(), List.of(), validLength, tornTail);
    }

    private record Frame(int offset, int type, int payloadLen) {}

    private record Write(boolean isDelete, String key, String value) {}

    private record Savepoint(int position, int setOffset) {}

    private record CommittedTxn(int commitOffset, Set<String> writtenKeys) {}

    private static final class Txn {
        final long id;
        final int beginOffset;
        final Map<String, String> snapshot;
        final List<Write> writes = new ArrayList<>();
        final Map<Integer, Savepoint> savepoints = new HashMap<>();
        final Set<String> readSet = new HashSet<>();

        Txn(long id, int beginOffset, Map<String, String> snapshot) {
            this.id = id;
            this.beginOffset = beginOffset;
            this.snapshot = snapshot;
        }
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
        TXN_TOO_LARGE, BAD_NAME, NO_SAVEPOINT,
        CHECKPOINT_MISMATCH, READ_MISMATCH
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
