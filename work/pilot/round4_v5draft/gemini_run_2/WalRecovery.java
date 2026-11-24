import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final int TYPE_CHECKPOINT = 8;
    private static final int TYPE_READ = 9;

    private WalRecovery() {}

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        Objects.requireNonNull(log, "log must not be null");
        final int len = log.length;

        // R2: Empty log
        if (len == 0) {
            return emptyResult(0, false);
        }

        // R3: Partial header (1..3 bytes)
        if (len < HEADER_SIZE) {
            for (int i = 0; i < len; i++) {
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

        // Phase 1: Frame scanning
        List<IntactRecord> intactRecords = new ArrayList<>();
        int p = HEADER_SIZE;
        int validLength;
        boolean tornTail;

        while (true) {
            // R5
            if (p == len) {
                validLength = len;
                tornTail = false;
                break;
            }
            // R6
            int rem = len - p;
            if (rem == 1 || rem == 2) {
                validLength = p;
                tornTail = true;
                break;
            }
            // R7
            int type = u8(log, p);
            if (type < TYPE_BEGIN || type > TYPE_READ) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }
            // R8
            int n = u16(log, p + 1);
            if (n > MAX_PAYLOAD) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }
            // R9
            if ((long) p + 5 + n > len) {
                validLength = p;
                tornTail = true;
                break;
            }
            // R10
            int endPayload = p + 3 + n;
            if (!verifyChecksum(log, p, endPayload)) {
                if (p + 5 + n == len) {
                    validLength = p;
                    tornTail = true;
                    break;
                }
                throw new WalException(Code.CHECKSUM_MISMATCH, p);
            }
            // R11
            intactRecords.add(new IntactRecord(p, type, n));
            p = p + 5 + n;
        }

        // Phase 2: Replay
        Map<String, String> currentState = new LinkedHashMap<>();
        Map<String, List<VersionedValue>> stateHistory = new HashMap<>();
        Map<String, Integer> lastCommittedWriteOffset = new HashMap<>();
        int currentVersion = 0;

        Set<Long> seenBeginTxns = new HashSet<>();
        TreeMap<Long, Txn> openTxns = new TreeMap<>();
        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();

        for (IntactRecord rec : intactRecords) {
            final int rp = rec.offset;
            final int pay = rp + 3;
            final int n = rec.payloadLen;

            switch (rec.type) {
                case TYPE_BEGIN -> {
                    // R13
                    if (n != 4) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    // R14
                    long txn = u32(log, pay);
                    if (txn == 0) {
                        throw new WalException(Code.BAD_TXN_ID, rp);
                    }
                    // R17
                    if (seenBeginTxns.contains(txn)) {
                        throw new WalException(Code.DUPLICATE_TXN, rp);
                    }
                    // R18
                    if (openTxns.size() >= MAX_OPEN_TXNS) {
                        throw new WalException(Code.TOO_MANY_OPEN, rp);
                    }
                    // R20
                    seenBeginTxns.add(txn);
                    openTxns.put(txn, new Txn(rp, currentVersion));
                }

                case TYPE_PUT -> {
                    // R13
                    if (n < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    int k = u8(log, pay + 4);
                    if (n < 7 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    int v = u16(log, pay + 5 + k);
                    if (n != 7 + k + v) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    // R14
                    long txn = u32(log, pay);
                    if (txn == 0) {
                        throw new WalException(Code.BAD_TXN_ID, rp);
                    }
                    // R15
                    checkKey(log, pay + 5, k, rp);
                    // R16
                    checkValue(log, pay + 7 + k, v, rp);
                    // R19
                    Txn t = openTxns.get(txn);
                    if (t == null) {
                        throw new WalException(Code.TXN_NOT_OPEN, rp);
                    }
                    // R19a
                    if (t.writes.size() >= MAX_WRITES) {
                        throw new WalException(Code.TXN_TOO_LARGE, rp);
                    }
                    // R21
                    String key = asciiString(log, pay + 5, k);
                    String value = asciiString(log, pay + 7 + k, v);
                    t.writes.add(new Write(false, key, value));
                }

                case TYPE_DELETE -> {
                    // R13
                    if (n < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    int k = u8(log, pay + 4);
                    if (n != 5 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    // R14
                    long txn = u32(log, pay);
                    if (txn == 0) {
                        throw new WalException(Code.BAD_TXN_ID, rp);
                    }
                    // R15
                    checkKey(log, pay + 5, k, rp);
                    // R19
                    Txn t = openTxns.get(txn);
                    if (t == null) {
                        throw new WalException(Code.TXN_NOT_OPEN, rp);
                    }
                    // R19a
                    if (t.writes.size() >= MAX_WRITES) {
                        throw new WalException(Code.TXN_TOO_LARGE, rp);
                    }
                    // R21
                    String key = asciiString(log, pay + 5, k);
                    t.writes.add(new Write(true, key, null));
                }

                case TYPE_COMMIT -> {
                    // R13
                    if (n != 4) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    // R14
                    long txn = u32(log, pay);
                    if (txn == 0) {
                        throw new WalException(Code.BAD_TXN_ID, rp);
                    }
                    // R19
                    Txn t = openTxns.get(txn);
                    if (t == null) {
                        throw new WalException(Code.TXN_NOT_OPEN, rp);
                    }
                    // R22
                    boolean conflicts = false;
                    for (String readKey : t.readSet) {
                        Integer lastWriteCommit = lastCommittedWriteOffset.get(readKey);
                        if (lastWriteCommit != null && lastWriteCommit > t.beginOffset) {
                            conflicts = true;
                            break;
                        }
                    }
                    if (!conflicts) {
                        currentVersion++;
                        for (Write w : t.writes) {
                            if (w.isDelete) {
                                currentState.remove(w.key);
                            } else {
                                currentState.put(w.key, w.value);
                            }
                            lastCommittedWriteOffset.put(w.key, rp);
                        }
                        for (Write w : t.writes) {
                            String valAfter = currentState.get(w.key);
                            List<VersionedValue> hist = stateHistory.computeIfAbsent(w.key, ignored -> new ArrayList<>());
                            if (!hist.isEmpty() && hist.get(hist.size() - 1).version == currentVersion) {
                                hist.set(hist.size() - 1, new VersionedValue(currentVersion, valAfter));
                            } else {
                                hist.add(new VersionedValue(currentVersion, valAfter));
                            }
                        }
                        committed.add(txn);
                    } else {
                        aborted.add(txn);
                    }
                    openTxns.remove(txn);
                }

                case TYPE_ABORT -> {
                    // R13
                    if (n != 4) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    // R14
                    long txn = u32(log, pay);
                    if (txn == 0) {
                        throw new WalException(Code.BAD_TXN_ID, rp);
                    }
                    // R19
                    Txn t = openTxns.get(txn);
                    if (t == null) {
                        throw new WalException(Code.TXN_NOT_OPEN, rp);
                    }
                    // R23
                    aborted.add(txn);
                    openTxns.remove(txn);
                }

                case TYPE_SAVEPOINT -> {
                    // R13
                    if (n != 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    // R14
                    long txn = u32(log, pay);
                    if (txn == 0) {
                        throw new WalException(Code.BAD_TXN_ID, rp);
                    }
                    // R16a
                    int name = u8(log, pay + 4);
                    if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) {
                        throw new WalException(Code.BAD_NAME, rp);
                    }
                    // R19
                    Txn t = openTxns.get(txn);
                    if (t == null) {
                        throw new WalException(Code.TXN_NOT_OPEN, rp);
                    }
                    // R23a
                    t.savepoints.put(name, new Savepoint(t.writes.size(), rp));
                }

                case TYPE_ROLLBACK -> {
                    // R13
                    if (n != 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    // R14
                    long txn = u32(log, pay);
                    if (txn == 0) {
                        throw new WalException(Code.BAD_TXN_ID, rp);
                    }
                    // R16a
                    int name = u8(log, pay + 4);
                    if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) {
                        throw new WalException(Code.BAD_NAME, rp);
                    }
                    // R19
                    Txn t = openTxns.get(txn);
                    if (t == null) {
                        throw new WalException(Code.TXN_NOT_OPEN, rp);
                    }
                    // R19b
                    Savepoint sp = t.savepoints.get(name);
                    if (sp == null) {
                        throw new WalException(Code.NO_SAVEPOINT, rp);
                    }
                    // R23b
                    t.writes.subList(sp.position, t.writes.size()).clear();
                    t.savepoints.values().removeIf(other -> other.setOffset > sp.setOffset);
                }

                case TYPE_CHECKPOINT -> {
                    // R13
                    if (n < 1) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    int c = u8(log, pay);
                    if (n != 1 + 4 * c) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    // R14
                    long[] listed = new long[c];
                    for (int i = 0; i < c; i++) {
                        long id = u32(log, pay + 1 + 4 * i);
                        if (id == 0) {
                            throw new WalException(Code.BAD_TXN_ID, rp);
                        }
                        listed[i] = id;
                    }
                    // R19c
                    if (c != openTxns.size()) {
                        throw new WalException(Code.CHECKPOINT_MISMATCH, rp);
                    }
                    int idx = 0;
                    for (long openId : openTxns.keySet()) {
                        if (listed[idx++] != openId) {
                            throw new WalException(Code.CHECKPOINT_MISMATCH, rp);
                        }
                    }
                    // R23c: no-op
                }

                case TYPE_READ -> {
                    // R13
                    if (n < 6) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    int k = u8(log, pay + 4);
                    if (n < 6 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    int f = u8(log, pay + 5 + k);
                    int v = 0;
                    if (f == 0) {
                        if (n != 6 + k) {
                            throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                        }
                    } else if (f == 1) {
                        if (n < 8 + k) {
                            throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                        }
                        v = u16(log, pay + 6 + k);
                        if (n != 8 + k + v) {
                            throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                        }
                    } else {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    // R14
                    long txn = u32(log, pay);
                    if (txn == 0) {
                        throw new WalException(Code.BAD_TXN_ID, rp);
                    }
                    // R15
                    checkKey(log, pay + 5, k, rp);
                    // R16
                    if (f == 1) {
                        checkValue(log, pay + 8 + k, v, rp);
                    }
                    // R19
                    Txn t = openTxns.get(txn);
                    if (t == null) {
                        throw new WalException(Code.TXN_NOT_OPEN, rp);
                    }
                    // R19d
                    String key = asciiString(log, pay + 5, k);
                    String view = getTxnView(t, key, stateHistory);
                    if (f == 0) {
                        if (view != null) {
                            throw new WalException(Code.READ_MISMATCH, rp);
                        }
                    } else {
                        String expected = asciiString(log, pay + 8 + k, v);
                        if (!expected.equals(view)) {
                            throw new WalException(Code.READ_MISMATCH, rp);
                        }
                    }
                    // R23d
                    t.readSet.add(key);
                }

                default -> throw new WalException(Code.UNKNOWN_TYPE, rp);
            }
        }

        List<Long> incomplete = new ArrayList<>(openTxns.keySet());
        return new Result(
                Collections.unmodifiableMap(currentState),
                Collections.unmodifiableList(committed),
                Collections.unmodifiableList(aborted),
                Collections.unmodifiableList(incomplete),
                validLength,
                tornTail
        );
    }

    private static Result emptyResult(int validLength, boolean tornTail) {
        return new Result(
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                validLength,
                tornTail
        );
    }

    private static int u8(byte[] log, int offset) {
        return log[offset] & 0xFF;
    }

    private static int u16(byte[] log, int offset) {
        return ((log[offset] & 0xFF) << 8) | (log[offset + 1] & 0xFF);
    }

    private static long u32(byte[] log, int offset) {
        return ((long) (log[offset] & 0xFF) << 24)
                | ((long) (log[offset + 1] & 0xFF) << 16)
                | ((long) (log[offset + 2] & 0xFF) << 8)
                | (long) (log[offset + 3] & 0xFF);
    }

    private static boolean verifyChecksum(byte[] log, int start, int endExclusive) {
        int s1 = 0;
        int s2 = 0;
        for (int i = start; i < endExclusive; i++) {
            s1 = (s1 + (log[i] & 0xFF)) % 255;
            s2 = (s2 + s1) % 255;
        }
        int storedS2 = log[endExclusive] & 0xFF;
        int storedS1 = log[endExclusive + 1] & 0xFF;
        return storedS2 == s2 && storedS1 == s1;
    }

    private static void checkKey(byte[] log, int offset, int k, int recordOffset) throws WalException {
        if (k < MIN_KEY_LEN || k > MAX_KEY_LEN) {
            throw new WalException(Code.BAD_KEY, recordOffset);
        }
        for (int i = 0; i < k; i++) {
            int b = log[offset + i] & 0xFF;
            if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                throw new WalException(Code.BAD_KEY, recordOffset);
            }
        }
    }

    private static void checkValue(byte[] log, int offset, int v, int recordOffset) throws WalException {
        for (int i = 0; i < v; i++) {
            int b = log[offset + i] & 0xFF;
            if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                throw new WalException(Code.BAD_VALUE, recordOffset);
            }
        }
    }

    private static String asciiString(byte[] log, int offset, int len) {
        if (len == 0) {
            return "";
        }
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            chars[i] = (char) (log[offset + i] & 0xFF);
        }
        return new String(chars);
    }

    private static String getTxnView(Txn t, String key, Map<String, List<VersionedValue>> stateHistory) {
        for (int i = t.writes.size() - 1; i >= 0; i--) {
            Write w = t.writes.get(i);
            if (w.key.equals(key)) {
                return w.isDelete ? null : w.value;
            }
        }
        List<VersionedValue> hist = stateHistory.get(key);
        if (hist == null) {
            return null;
        }
        int lo = 0;
        int hi = hist.size() - 1;
        int best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (hist.get(mid).version <= t.snapshotVersion) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best == -1 ? null : hist.get(best).value;
    }

    private record IntactRecord(int offset, int type, int payloadLen) {}

    private record Write(boolean isDelete, String key, String value) {}

    private record Savepoint(int position, int setOffset) {}

    private record VersionedValue(int version, String value) {}

    private static final class Txn {
        final int beginOffset;
        final int snapshotVersion;
        final List<Write> writes = new ArrayList<>();
        final Map<Integer, Savepoint> savepoints = new HashMap<>();
        final Set<String> readSet = new HashSet<>();

        Txn(int beginOffset, int snapshotVersion) {
            this.beginOffset = beginOffset;
            this.snapshotVersion = snapshotVersion;
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
