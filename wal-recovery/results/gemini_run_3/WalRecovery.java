import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class WalRecovery {
    private WalRecovery() {}

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
        CHECKPOINT_MISMATCH, READ_MISMATCH, RANGE_MISMATCH
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

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        // R1
        if (log == null) {
            throw new NullPointerException();
        }
        int L = log.length;
        // R2
        if (L == 0) {
            return new Result(Collections.emptyMap(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), 0, false);
        }
        // R3
        if (L < HEADER_SIZE) {
            for (int i = 0; i < L; i++) {
                if (log[i] != MAGIC[i]) {
                    throw new WalException(Code.BAD_MAGIC, 0);
                }
            }
            return new Result(Collections.emptyMap(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), 0, true);
        }
        // R4
        if (log[0] != MAGIC[0] || log[1] != MAGIC[1] || log[2] != MAGIC[2] || log[3] != MAGIC[3]) {
            throw new WalException(Code.BAD_MAGIC, 0);
        }

        // Phase 1: frames
        IntList intactOffsets = new IntList(Math.min(L / 9 + 16, 1_500_000));
        int validLength;
        boolean tornTail;
        int p = HEADER_SIZE;
        while (true) {
            // R5
            if (p == L) {
                validLength = L;
                tornTail = false;
                break;
            }
            // R6
            if (L - p <= 2) {
                validLength = p;
                tornTail = true;
                break;
            }
            // R7
            int type = log[p] & 0xFF;
            if (type < 1 || type > 10) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }
            // R8
            int N = ((log[p + 1] & 0xFF) << 8) | (log[p + 2] & 0xFF);
            if (N > MAX_PAYLOAD) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }
            // R9
            int end = p + 5 + N;
            if (end > L) {
                validLength = p;
                tornTail = true;
                break;
            }
            // R10
            if (!checkFletcher16(log, p, 3 + N, log[p + 3 + N] & 0xFF, log[p + 4 + N] & 0xFF)) {
                if (end == L) {
                    validLength = p;
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }
            // R11
            intactOffsets.add(p);
            p = end;
        }

        // Phase 2: replay
        int numRecords = intactOffsets.size();
        if (numRecords == 0) {
            return new Result(new HashMap<>(), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), validLength, tornTail);
        }

        StateStore store = new StateStore();
        IntHashSet seenTxns = new IntHashSet(1024);
        Txn[] openSlots = new Txn[MAX_OPEN_TXNS];
        int openCount = 0;

        ArrayList<Long> committed = new ArrayList<>();
        ArrayList<Long> aborted = new ArrayList<>();

        int[] offsets = intactOffsets.data;
        for (int idx = 0; idx < numRecords; idx++) {
            int recOff = offsets[idx];
            int type = log[recOff] & 0xFF;
            int N = ((log[recOff + 1] & 0xFF) << 8) | (log[recOff + 2] & 0xFF);
            int pay = recOff + 3;

            switch (type) {
                case 1: { // BEGIN
                    if (N != 4) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int txnId = readIntBE(log, pay);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, recOff);
                    if (seenTxns.contains(txnId)) throw new WalException(Code.DUPLICATE_TXN, recOff);
                    if (openCount == MAX_OPEN_TXNS) throw new WalException(Code.TOO_MANY_OPEN, recOff);

                    seenTxns.add(txnId);
                    Snapshot snap = store.takeSnapshot();
                    openSlots[openCount++] = new Txn(txnId, snap);
                    break;
                }
                case 2: { // PUT
                    if (N < 5) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int k = log[pay + 4] & 0xFF;
                    if (N < 7 + k) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int v = ((log[pay + 5 + k] & 0xFF) << 8) | (log[pay + 6 + k] & 0xFF);
                    if (N != 7 + k + v) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);

                    int txnId = readIntBE(log, pay);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, recOff);
                    checkKey(log, pay + 5, k, recOff);
                    checkValue(log, pay + 7 + k, v, recOff);

                    Txn txn = findOpenTxn(openSlots, openCount, txnId, recOff);
                    if (txn.writeCount == MAX_WRITES) throw new WalException(Code.TXN_TOO_LARGE, recOff);

                    String key = extractString(log, pay + 5, k);
                    String val = extractString(log, pay + 7 + k, v);
                    txn.addWrite(key, val);
                    break;
                }
                case 3: { // DELETE
                    if (N < 5) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int k = log[pay + 4] & 0xFF;
                    if (N != 5 + k) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);

                    int txnId = readIntBE(log, pay);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, recOff);
                    checkKey(log, pay + 5, k, recOff);

                    Txn txn = findOpenTxn(openSlots, openCount, txnId, recOff);
                    if (txn.writeCount == MAX_WRITES) throw new WalException(Code.TXN_TOO_LARGE, recOff);

                    String key = extractString(log, pay + 5, k);
                    txn.addWrite(key, null);
                    break;
                }
                case 4: { // COMMIT
                    if (N != 4) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int txnId = readIntBE(log, pay);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, recOff);

                    int slotIdx = findOpenTxnIndex(openSlots, openCount, txnId, recOff);
                    Txn txn = openSlots[slotIdx];
                    openSlots[slotIdx] = openSlots[--openCount];
                    openSlots[openCount] = null;

                    boolean conflicts = txn.hasConflict(store);
                    txn.snapshot.release();

                    if (!conflicts) {
                        // Notify remaining open transactions of written keys
                        for (int i = 0; i < txn.writeCount; i++) {
                            String wKey = txn.writeKeys[i];
                            boolean duplicateEarlier = false;
                            for (int j = 0; j < i; j++) {
                                if (txn.writeKeys[j].equals(wKey)) {
                                    duplicateEarlier = true;
                                    break;
                                }
                            }
                            if (!duplicateEarlier) {
                                for (int s = 0; s < openCount; s++) {
                                    openSlots[s].onOtherCommittedWrite(wKey);
                                }
                            }
                        }
                        // Apply writes to state
                        for (int i = 0; i < txn.writeCount; i++) {
                            String wKey = txn.writeKeys[i];
                            String wVal = txn.writeValues[i];
                            if (wVal != null) {
                                store.put(wKey, wVal);
                            } else {
                                store.delete(wKey);
                            }
                        }
                        committed.add(txnId & 0xFFFFFFFFL);
                    } else {
                        aborted.add(txnId & 0xFFFFFFFFL);
                    }
                    break;
                }
                case 5: { // ABORT
                    if (N != 4) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int txnId = readIntBE(log, pay);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, recOff);

                    int slotIdx = findOpenTxnIndex(openSlots, openCount, txnId, recOff);
                    Txn txn = openSlots[slotIdx];
                    openSlots[slotIdx] = openSlots[--openCount];
                    openSlots[openCount] = null;

                    txn.snapshot.release();
                    aborted.add(txnId & 0xFFFFFFFFL);
                    break;
                }
                case 6: { // SAVEPOINT
                    if (N != 5) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int txnId = readIntBE(log, pay);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, recOff);
                    int name = log[pay + 4] & 0xFF;
                    if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) {
                        throw new WalException(Code.BAD_NAME, recOff);
                    }

                    Txn txn = findOpenTxn(openSlots, openCount, txnId, recOff);
                    txn.setSavepoint(name, recOff);
                    break;
                }
                case 7: { // ROLLBACK
                    if (N != 5) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int txnId = readIntBE(log, pay);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, recOff);
                    int name = log[pay + 4] & 0xFF;
                    if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) {
                        throw new WalException(Code.BAD_NAME, recOff);
                    }

                    Txn txn = findOpenTxn(openSlots, openCount, txnId, recOff);
                    if (!txn.hasSavepoint(name)) {
                        throw new WalException(Code.NO_SAVEPOINT, recOff);
                    }
                    txn.rollbackTo(name);
                    break;
                }
                case 8: { // CHECKPOINT
                    if (N < 1) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int c = log[pay] & 0xFF;
                    if (N != 1 + 4 * c) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);

                    for (int i = 0; i < c; i++) {
                        int listedId = readIntBE(log, pay + 1 + 4 * i);
                        if (listedId == 0) throw new WalException(Code.BAD_TXN_ID, recOff);
                    }

                    if (c != openCount) {
                        throw new WalException(Code.CHECKPOINT_MISMATCH, recOff);
                    }
                    if (openCount > 0) {
                        long[] openIds = new long[openCount];
                        for (int i = 0; i < openCount; i++) {
                            openIds[i] = openSlots[i].id & 0xFFFFFFFFL;
                        }
                        Arrays.sort(openIds);
                        for (int i = 0; i < c; i++) {
                            long listedId = readIntBE(log, pay + 1 + 4 * i) & 0xFFFFFFFFL;
                            if (listedId != openIds[i]) {
                                throw new WalException(Code.CHECKPOINT_MISMATCH, recOff);
                            }
                        }
                    }
                    break;
                }
                case 9: { // READ
                    if (N < 6) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int k = log[pay + 4] & 0xFF;
                    if (N < 6 + k) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int f = log[pay + 5 + k] & 0xFF;
                    int v = 0;
                    if (f == 0) {
                        if (N != 6 + k) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    } else if (f == 1) {
                        if (N < 8 + k) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                        v = ((log[pay + 6 + k] & 0xFF) << 8) | (log[pay + 7 + k] & 0xFF);
                        if (N != 8 + k + v) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    } else {
                        throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    }

                    int txnId = readIntBE(log, pay);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, recOff);
                    checkKey(log, pay + 5, k, recOff);
                    if (f == 1) {
                        checkValue(log, pay + 8 + k, v, recOff);
                    }

                    Txn txn = findOpenTxn(openSlots, openCount, txnId, recOff);
                    String key = extractString(log, pay + 5, k);
                    String actualVal = txn.getView(key, store);
                    if (f == 0) {
                        if (actualVal != null) {
                            throw new WalException(Code.READ_MISMATCH, recOff);
                        }
                    } else {
                        if (actualVal == null || !equalsBytes(actualVal, log, pay + 8 + k, v)) {
                            throw new WalException(Code.READ_MISMATCH, recOff);
                        }
                    }
                    txn.addReadKey(key);
                    break;
                }
                case 10: { // RANGE
                    if (N < 6) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int k1 = log[pay + 4] & 0xFF;
                    if (N < 6 + k1) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);
                    int k2 = log[pay + 5 + k1] & 0xFF;
                    if (N != 10 + k1 + k2) throw new WalException(Code.MALFORMED_PAYLOAD, recOff);

                    int txnId = readIntBE(log, pay);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, recOff);
                    checkKey(log, pay + 5, k1, recOff);
                    checkKey(log, pay + 6 + k1, k2, recOff);

                    Txn txn = findOpenTxn(openSlots, openCount, txnId, recOff);
                    String lo = extractString(log, pay + 5, k1);
                    String hi = extractString(log, pay + 6 + k1, k2);
                    long expectedCount = readIntBE(log, pay + 6 + k1 + k2) & 0xFFFFFFFFL;

                    int actualCount = txn.countViewRange(lo, hi, store);
                    if (actualCount != expectedCount) {
                        throw new WalException(Code.RANGE_MISMATCH, recOff);
                    }
                    txn.addReadRange(lo, hi);
                    break;
                }
                default:
                    throw new AssertionError();
            }
        }

        // Build incomplete list (R26)
        ArrayList<Long> incomplete = new ArrayList<>(openCount);
        if (openCount > 0) {
            long[] rem = new long[openCount];
            for (int i = 0; i < openCount; i++) {
                rem[i] = openSlots[i].id & 0xFFFFFFFFL;
            }
            Arrays.sort(rem);
            for (long id : rem) {
                incomplete.add(id);
            }
        }

        return new Result(store.toMap(), committed, aborted, incomplete, validLength, tornTail);
    }

    private static boolean checkFletcher16(byte[] log, int off, int len, int expectedS2, int expectedS1) {
        int s1 = 0;
        int s2 = 0;
        int end = off + len;
        int i = off;
        while (i < end) {
            int chunkEnd = Math.min(end, i + 4096);
            while (i < chunkEnd) {
                s1 += log[i++] & 0xFF;
                s2 += s1;
            }
            s1 %= 255;
            s2 %= 255;
        }
        return s1 == expectedS1 && s2 == expectedS2;
    }

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static void checkKey(byte[] log, int off, int len, int recOff) throws WalException {
        if (len < MIN_KEY_LEN || len > MAX_KEY_LEN) {
            throw new WalException(Code.BAD_KEY, recOff);
        }
        int end = off + len;
        for (int i = off; i < end; i++) {
            int b = log[i] & 0xFF;
            if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                throw new WalException(Code.BAD_KEY, recOff);
            }
        }
    }

    private static void checkValue(byte[] log, int off, int len, int recOff) throws WalException {
        int end = off + len;
        for (int i = off; i < end; i++) {
            int b = log[i] & 0xFF;
            if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                throw new WalException(Code.BAD_VALUE, recOff);
            }
        }
    }

    private static String extractString(byte[] log, int off, int len) {
        if (len == 0) return "";
        return new String(log, off, len, StandardCharsets.ISO_8859_1);
    }

    private static boolean equalsBytes(String s, byte[] log, int off, int len) {
        if (s.length() != len) return false;
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) != (log[off + i] & 0xFF)) return false;
        }
        return true;
    }

    private static Txn findOpenTxn(Txn[] openSlots, int openCount, int txnId, int recOff) throws WalException {
        for (int i = 0; i < openCount; i++) {
            if (openSlots[i].id == txnId) {
                return openSlots[i];
            }
        }
        throw new WalException(Code.TXN_NOT_OPEN, recOff);
    }

    private static int findOpenTxnIndex(Txn[] openSlots, int openCount, int txnId, int recOff) throws WalException {
        for (int i = 0; i < openCount; i++) {
            if (openSlots[i].id == txnId) {
                return i;
            }
        }
        throw new WalException(Code.TXN_NOT_OPEN, recOff);
    }

    // --- Primitive collections for fast, low-memory operation ---

    private static final class IntList {
        int[] data;
        int size;

        IntList(int initialCapacity) {
            data = new int[Math.max(16, initialCapacity)];
        }

        void add(int val) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = val;
        }

        int size() {
            return size;
        }
    }

    private static final class IntHashSet {
        private int[] keys;
        private int size;
        private int threshold;

        IntHashSet(int capacity) {
            int cap = Integer.highestOneBit(Math.max(16, capacity) - 1) << 1;
            keys = new int[cap];
            threshold = (int) (cap * 0.7f);
        }

        private static int mix(int x) {
            x ^= x >>> 16;
            x *= 0x7feb352d;
            x ^= x >>> 15;
            x *= 0x846ca68b;
            x ^= x >>> 16;
            return x;
        }

        boolean contains(int key) {
            int mask = keys.length - 1;
            int idx = mix(key) & mask;
            while (true) {
                int cur = keys[idx];
                if (cur == 0) return false;
                if (cur == key) return true;
                idx = (idx + 1) & mask;
            }
        }

        void add(int key) {
            if (size >= threshold) {
                rehash();
            }
            int mask = keys.length - 1;
            int idx = mix(key) & mask;
            while (true) {
                int cur = keys[idx];
                if (cur == 0) {
                    keys[idx] = key;
                    size++;
                    return;
                }
                if (cur == key) return;
                idx = (idx + 1) & mask;
            }
        }

        private void rehash() {
            int[] old = keys;
            int newCap = old.length << 1;
            int[] next = new int[newCap];
            int mask = newCap - 1;
            for (int k : old) {
                if (k != 0) {
                    int idx = mix(k) & mask;
                    while (next[idx] != 0) {
                        idx = (idx + 1) & mask;
                    }
                    next[idx] = k;
                }
            }
            keys = next;
            threshold = (int) (newCap * 0.7f);
        }
    }

    // --- Transaction representation ---

    private static final class Txn {
        final int id;
        final Snapshot snapshot;

        // Write list (max 8)
        final String[] writeKeys = new String[MAX_WRITES];
        final String[] writeValues = new String[MAX_WRITES]; // null means DELETE
        int writeCount = 0;

        // Savepoints (name byte 0x21..0x7E -> index name - 0x21)
        int[] spPositions;
        int[] spOffsets; // -1 if absent

        // Read tracking & conflict detection
        HashSet<String> readSet;
        ArrayList<String> rangeLos;
        ArrayList<String> rangeHis;
        boolean conflicted = false;

        // Concurrent committed writes by other txns since this txn's BEGIN
        TreeSet<String> concurrentWrites;

        Txn(int id, Snapshot snapshot) {
            this.id = id;
            this.snapshot = snapshot;
        }

        void addWrite(String key, String val) {
            writeKeys[writeCount] = key;
            writeValues[writeCount] = val;
            writeCount++;
        }

        void setSavepoint(int name, int recOff) {
            if (spOffsets == null) {
                int len = NAME_BYTE_MAX - NAME_BYTE_MIN + 1;
                spPositions = new int[len];
                spOffsets = new int[len];
                Arrays.fill(spOffsets, -1);
            }
            int idx = name - NAME_BYTE_MIN;
            spPositions[idx] = writeCount;
            spOffsets[idx] = recOff;
        }

        boolean hasSavepoint(int name) {
            if (spOffsets == null) return false;
            return spOffsets[name - NAME_BYTE_MIN] >= 0;
        }

        void rollbackTo(int name) {
            int idx = name - NAME_BYTE_MIN;
            int targetPos = spPositions[idx];
            int targetOff = spOffsets[idx];
            for (int i = targetPos; i < writeCount; i++) {
                writeKeys[i] = null;
                writeValues[i] = null;
            }
            writeCount = targetPos;
            for (int i = 0; i < spOffsets.length; i++) {
                if (spOffsets[i] > targetOff) {
                    spOffsets[i] = -1;
                }
            }
        }

        String getView(String key, StateStore store) {
            for (int i = writeCount - 1; i >= 0; i--) {
                if (writeKeys[i].equals(key)) {
                    return writeValues[i];
                }
            }
            return snapshot.get(key, store);
        }

        int countViewRange(String lo, String hi, StateStore store) {
            if (lo.compareTo(hi) > 0) {
                return 0;
            }
            int count = snapshot.countRange(lo, hi, store);
            // Adjust for unique keys in write list that fall within [lo, hi]
            for (int i = writeCount - 1; i >= 0; i--) {
                String k = writeKeys[i];
                boolean seenLater = false;
                for (int j = writeCount - 1; j > i; j--) {
                    if (writeKeys[j].equals(k)) {
                        seenLater = true;
                        break;
                    }
                }
                if (seenLater) continue;

                if (k.compareTo(lo) >= 0 && k.compareTo(hi) <= 0) {
                    boolean inSnap = snapshot.get(k, store) != null;
                    boolean inWrite = writeValues[i] != null;
                    if (inWrite && !inSnap) {
                        count++;
                    } else if (!inWrite && inSnap) {
                        count--;
                    }
                }
            }
            return count;
        }

        void onOtherCommittedWrite(String key) {
            if (conflicted) return;
            if (readSet != null && readSet.contains(key)) {
                conflicted = true;
                concurrentWrites = null;
                return;
            }
            if (concurrentWrites == null) {
                concurrentWrites = new TreeSet<>();
            }
            concurrentWrites.add(key);
        }

        void addReadKey(String key) {
            if (conflicted) return;
            if (concurrentWrites != null && concurrentWrites.contains(key)) {
                conflicted = true;
                concurrentWrites = null;
                return;
            }
            if (readSet == null) {
                readSet = new HashSet<>();
            }
            readSet.add(key);
        }

        void addReadRange(String lo, String hi) {
            if (conflicted) return;
            if (lo.compareTo(hi) > 0) {
                return; // Empty range can never contain any key
            }
            if (concurrentWrites != null && !concurrentWrites.isEmpty()) {
                String ceil = concurrentWrites.ceiling(lo);
                if (ceil != null && ceil.compareTo(hi) <= 0) {
                    conflicted = true;
                    concurrentWrites = null;
                    return;
                }
            }
            if (rangeLos == null) {
                rangeLos = new ArrayList<>();
                rangeHis = new ArrayList<>();
            }
            rangeLos.add(lo);
            rangeHis.add(hi);
        }

        boolean hasConflict(StateStore store) {
            if (conflicted) return true;
            if (concurrentWrites == null || concurrentWrites.isEmpty()) return false;
            if (rangeLos == null || rangeLos.isEmpty()) return false;

            int numRanges = rangeLos.size();
            int numWrites = concurrentWrites.size();

            // Choose the faster strategy depending on sizes
            if (numRanges <= numWrites * 2 || numRanges <= 32) {
                for (int i = 0; i < numRanges; i++) {
                    String lo = rangeLos.get(i);
                    String hi = rangeHis.get(i);
                    String ceil = concurrentWrites.ceiling(lo);
                    if (ceil != null && ceil.compareTo(hi) <= 0) {
                        conflicted = true;
                        return true;
                    }
                }
                return false;
            } else {
                // Sort and merge intervals, then probe each concurrent write in O(log R)
                Integer[] order = new Integer[numRanges];
                for (int i = 0; i < numRanges; i++) order[i] = i;
                Arrays.sort(order, (a, b) -> rangeLos.get(a).compareTo(rangeLos.get(b)));

                String[] mergedLo = new String[numRanges];
                String[] mergedHi = new String[numRanges];
                int m = 0;
                String curLo = rangeLos.get(order[0]);
                String curHi = rangeHis.get(order[0]);
                for (int i = 1; i < numRanges; i++) {
                    int idx = order[i];
                    String nextLo = rangeLos.get(idx);
                    String nextHi = rangeHis.get(idx);
                    if (nextLo.compareTo(curHi) <= 0) {
                        if (nextHi.compareTo(curHi) > 0) {
                            curHi = nextHi;
                        }
                    } else {
                        mergedLo[m] = curLo;
                        mergedHi[m] = curHi;
                        m++;
                        curLo = nextLo;
                        curHi = nextHi;
                    }
                }
                mergedLo[m] = curLo;
                mergedHi[m] = curHi;
                m++;

                for (String wKey : concurrentWrites) {
                    int low = 0, high = m - 1;
                    int best = -1;
                    while (low <= high) {
                        int mid = (low + high) >>> 1;
                        if (mergedLo[mid].compareTo(wKey) <= 0) {
                            best = mid;
                            low = mid + 1;
                        } else {
                            high = mid - 1;
                        }
                    }
                    if (best >= 0 && wKey.compareTo(mergedHi[best]) <= 0) {
                        conflicted = true;
                        return true;
                    }
                }
                return false;
            }
        }
    }

    // --- State Store & Copy-on-Write / Persistent Order-Statistic Tree ---

    private static final class Snapshot {
        private int activePinIndex; // >= 0 while pinned to live state with delta tracking, -1 when frozen
        private TreeNode frozenRoot;
        private HashMap<String, String> undoMap; // key -> original value at snapshot time (null = absent)
        private TreeSet<String> addedKeys;       // keys absent in snapshot, currently present in live state
        private TreeSet<String> removedKeys;     // keys present in snapshot, currently absent in live state

        Snapshot(int pinIndex) {
            this.activePinIndex = pinIndex;
        }

        Snapshot(TreeNode frozenRoot) {
            this.activePinIndex = -1;
            this.frozenRoot = frozenRoot;
        }

        boolean isPinnedLive() {
            return activePinIndex >= 0;
        }

        void onLiveMutation(String key, boolean wasPresent, String oldVal, boolean nowPresent) {
            boolean origPresent;
            if (undoMap == null) {
                undoMap = new HashMap<>();
            }
            if (!undoMap.containsKey(key)) {
                undoMap.put(key, oldVal);
                origPresent = wasPresent;
            } else {
                origPresent = undoMap.get(key) != null;
            }

            if (origPresent == nowPresent) {
                if (origPresent) {
                    if (removedKeys != null) removedKeys.remove(key);
                } else {
                    if (addedKeys != null) addedKeys.remove(key);
                }
            } else if (!origPresent) { // nowPresent is true
                if (addedKeys == null) addedKeys = new TreeSet<>();
                addedKeys.add(key);
            } else { // origPresent is true, nowPresent is false
                if (removedKeys == null) removedKeys = new TreeSet<>();
                removedKeys.add(key);
            }
        }

        void freezeFromCurrent(TreeNode currentRoot) {
            TreeNode r = currentRoot;
            if (undoMap != null) {
                for (Map.Entry<String, String> e : undoMap.entrySet()) {
                    String k = e.getKey();
                    String origVal = e.getValue();
                    if (origVal == null) {
                        r = OSTree.deletePersistent(r, k);
                    } else {
                        r = OSTree.putPersistent(r, k, origVal);
                    }
                }
            }
            this.frozenRoot = r;
            this.activePinIndex = -1;
            this.undoMap = null;
            this.addedKeys = null;
            this.removedKeys = null;
        }

        String get(String key, StateStore store) {
            if (activePinIndex >= 0) {
                if (undoMap != null && undoMap.containsKey(key)) {
                    return undoMap.get(key);
                }
                return store.getLive(key);
            } else {
                return OSTree.get(frozenRoot, key);
            }
        }

        int countRange(String lo, String hi, StateStore store) {
            if (activePinIndex >= 0) {
                // If delta sets grew large, materialize frozen tree so repeated range queries are O(log N)
                int deltaSize = (addedKeys != null ? addedKeys.size() : 0)
                        + (removedKeys != null ? removedKeys.size() : 0);
                if (deltaSize > 64) {
                    store.unpinAndFreeze(this);
                    return OSTree.countRange(frozenRoot, lo, hi);
                }
                int count = store.countLiveRange(lo, hi);
                if (addedKeys != null && !addedKeys.isEmpty()) {
                    count -= addedKeys.subSet(lo, true, hi, true).size();
                }
                if (removedKeys != null && !removedKeys.isEmpty()) {
                    count += removedKeys.subSet(lo, true, hi, true).size();
                }
                return count;
            } else {
                return OSTree.countRange(frozenRoot, lo, hi);
            }
        }

        void release() {
            if (activePinIndex >= 0) {
                // Will be cleaned up lazily or immediately via StateStore if needed,
                // mark inactive so mutations skip it
                activePinIndex = -2;
                undoMap = null;
                addedKeys = null;
                removedKeys = null;
            }
            frozenRoot = null;
        }
    }

    private static final class StateStore {
        private TreeNode root;
        private boolean rootShared = false;
        private final Snapshot[] pinned = new Snapshot[MAX_OPEN_TXNS];
        private int pinnedCount = 0;

        Snapshot takeSnapshot() {
            compactPinned();
            if (pinnedCount < MAX_OPEN_TXNS) {
                Snapshot s = new Snapshot(pinnedCount);
                pinned[pinnedCount++] = s;
                return s;
            } else {
                rootShared = true;
                return new Snapshot(root);
            }
        }

        private void compactPinned() {
            int w = 0;
            for (int r = 0; r < pinnedCount; r++) {
                Snapshot s = pinned[r];
                if (s.activePinIndex >= 0) {
                    s.activePinIndex = w;
                    pinned[w++] = s;
                }
            }
            for (int i = w; i < pinnedCount; i++) {
                pinned[i] = null;
            }
            pinnedCount = w;
        }

        void unpinAndFreeze(Snapshot snap) {
            if (snap.activePinIndex < 0) return;
            rootShared = true;
            snap.freezeFromCurrent(root);
            compactPinned();
        }

        String getLive(String key) {
            return OSTree.get(root, key);
        }

        int countLiveRange(String lo, String hi) {
            return OSTree.countRange(root, lo, hi);
        }

        void put(String key, String val) {
            compactPinned();
            if (pinnedCount > 0) {
                String oldVal = OSTree.get(root, key);
                boolean wasPresent = oldVal != null;
                for (int i = 0; i < pinnedCount; i++) {
                    Snapshot s = pinned[i];
                    if (s.undoMap != null && s.undoMap.size() >= 4096) {
                        rootShared = true;
                        s.freezeFromCurrent(root);
                    } else {
                        s.onLiveMutation(key, wasPresent, oldVal, true);
                    }
                }
                compactPinned();
            }
            if (rootShared) {
                root = OSTree.putPersistent(root, key, val);
            } else {
                root = OSTree.putMutable(root, key, val);
            }
        }

        void delete(String key) {
            String oldVal = OSTree.get(root, key);
            if (oldVal == null) {
                return;
            }
            compactPinned();
            if (pinnedCount > 0) {
                for (int i = 0; i < pinnedCount; i++) {
                    Snapshot s = pinned[i];
                    if (s.undoMap != null && s.undoMap.size() >= 4096) {
                        rootShared = true;
                        s.freezeFromCurrent(root);
                    } else {
                        s.onLiveMutation(key, true, oldVal, false);
                    }
                }
                compactPinned();
            }
            if (rootShared) {
                root = OSTree.deletePersistent(root, key);
            } else {
                root = OSTree.deleteMutable(root, key);
            }
        }

        Map<String, String> toMap() {
            int sz = root == null ? 0 : root.size;
            HashMap<String, String> map = new HashMap<>((int) (sz / 0.75f) + 1);
            OSTree.collect(root, map);
            return map;
        }
    }

    // --- AVL Order-Statistic Tree (supports both in-place and persistent updates) ---

    private static final class TreeNode {
        String key;
        String value;
        TreeNode left;
        TreeNode right;
        int height;
        int size;

        TreeNode(String key, String value, TreeNode left, TreeNode right) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            this.height = 1 + Math.max(height(left), height(right));
            this.size = 1 + size(left) + size(right);
        }

        void updateStats() {
            int lh = left == null ? 0 : left.height;
            int rh = right == null ? 0 : right.height;
            this.height = 1 + (lh > rh ? lh : rh);
            this.size = 1 + (left == null ? 0 : left.size) + (right == null ? 0 : right.size);
        }

        static int height(TreeNode n) {
            return n == null ? 0 : n.height;
        }

        static int size(TreeNode n) {
            return n == null ? 0 : n.size;
        }
    }

    private static final class OSTree {
        static String get(TreeNode n, String key) {
            while (n != null) {
                int cmp = key.compareTo(n.key);
                if (cmp == 0) return n.value;
                n = cmp < 0 ? n.left : n.right;
            }
            return null;
        }

        static int countRange(TreeNode root, String lo, String hi) {
            if (root == null || lo.compareTo(hi) > 0) return 0;
            return countLessOrEqual(root, hi) - countStrictlyLess(root, lo);
        }

        private static int countStrictlyLess(TreeNode n, String key) {
            int count = 0;
            while (n != null) {
                int cmp = key.compareTo(n.key);
                if (cmp <= 0) {
                    n = n.left;
                } else {
                    count += 1 + TreeNode.size(n.left);
                    n = n.right;
                }
            }
            return count;
        }

        private static int countLessOrEqual(TreeNode n, String key) {
            int count = 0;
            while (n != null) {
                int cmp = key.compareTo(n.key);
                if (cmp < 0) {
                    n = n.left;
                } else if (cmp > 0) {
                    count += 1 + TreeNode.size(n.left);
                    n = n.right;
                } else {
                    count += 1 + TreeNode.size(n.left);
                    break;
                }
            }
            return count;
        }

        static void collect(TreeNode n, Map<String, String> out) {
            if (n == null) return;
            collect(n.left, out);
            out.put(n.key, n.value);
            collect(n.right, out);
        }

        // --- Mutable operations ---

        static TreeNode putMutable(TreeNode n, String key, String val) {
            if (n == null) {
                return new TreeNode(key, val, null, null);
            }
            int cmp = key.compareTo(n.key);
            if (cmp == 0) {
                n.value = val;
                return n;
            } else if (cmp < 0) {
                n.left = putMutable(n.left, key, val);
            } else {
                n.right = putMutable(n.right, key, val);
            }
            return balanceMutable(n);
        }

        static TreeNode deleteMutable(TreeNode n, String key) {
            if (n == null) return null;
            int cmp = key.compareTo(n.key);
            if (cmp < 0) {
                n.left = deleteMutable(n.left, key);
            } else if (cmp > 0) {
                n.right = deleteMutable(n.right, key);
            } else {
                if (n.left == null) return n.right;
                if (n.right == null) return n.left;
                TreeNode min = minNode(n.right);
                n.key = min.key;
                n.value = min.value;
                n.right = deleteMinMutable(n.right);
            }
            return balanceMutable(n);
        }

        private static TreeNode deleteMinMutable(TreeNode n) {
            if (n.left == null) return n.right;
            n.left = deleteMinMutable(n.left);
            return balanceMutable(n);
        }

        private static TreeNode balanceMutable(TreeNode n) {
            n.updateStats();
            int bf = TreeNode.height(n.left) - TreeNode.height(n.right);
            if (bf > 1) {
                if (TreeNode.height(n.left.left) < TreeNode.height(n.left.right)) {
                    n.left = rotateLeftMutable(n.left);
                }
                return rotateRightMutable(n);
            }
            if (bf < -1) {
                if (TreeNode.height(n.right.right) < TreeNode.height(n.right.left)) {
                    n.right = rotateRightMutable(n.right);
                }
                return rotateLeftMutable(n);
            }
            return n;
        }

        private static TreeNode rotateRightMutable(TreeNode y) {
            TreeNode x = y.left;
            y.left = x.right;
            x.right = y;
            y.updateStats();
            x.updateStats();
            return x;
        }

        private static TreeNode rotateLeftMutable(TreeNode x) {
            TreeNode y = x.right;
            x.right = y.left;
            y.left = x;
            x.updateStats();
            y.updateStats();
            return y;
        }

        // --- Persistent (path-copying) operations ---

        static TreeNode putPersistent(TreeNode n, String key, String val) {
            if (n == null) {
                return new TreeNode(key, val, null, null);
            }
            int cmp = key.compareTo(n.key);
            if (cmp == 0) {
                if (n.value.equals(val)) return n;
                return new TreeNode(key, val, n.left, n.right);
            } else if (cmp < 0) {
                TreeNode nl = putPersistent(n.left, key, val);
                return balancePersistent(n.key, n.value, nl, n.right);
            } else {
                TreeNode nr = putPersistent(n.right, key, val);
                return balancePersistent(n.key, n.value, n.left, nr);
            }
        }

        static TreeNode deletePersistent(TreeNode n, String key) {
            if (n == null) return null;
            int cmp = key.compareTo(n.key);
            if (cmp < 0) {
                TreeNode nl = deletePersistent(n.left, key);
                if (nl == n.left) return n;
                return balancePersistent(n.key, n.value, nl, n.right);
            } else if (cmp > 0) {
                TreeNode nr = deletePersistent(n.right, key);
                if (nr == n.right) return n;
                return balancePersistent(n.key, n.value, n.left, nr);
            } else {
                if (n.left == null) return n.right;
                if (n.right == null) return n.left;
                TreeNode min = minNode(n.right);
                TreeNode nr = deleteMinPersistent(n.right);
                return balancePersistent(min.key, min.value, n.left, nr);
            }
        }

        private static TreeNode deleteMinPersistent(TreeNode n) {
            if (n.left == null) return n.right;
            TreeNode nl = deleteMinPersistent(n.left);
            return balancePersistent(n.key, n.value, nl, n.right);
        }

        private static TreeNode minNode(TreeNode n) {
            while (n.left != null) n = n.left;
            return n;
        }

        private static TreeNode balancePersistent(String k, String v, TreeNode l, TreeNode r) {
            int bf = TreeNode.height(l) - TreeNode.height(r);
            if (bf > 1) {
                if (TreeNode.height(l.left) >= TreeNode.height(l.right)) {
                    TreeNode nr = new TreeNode(k, v, l.right, r);
                    return new TreeNode(l.key, l.value, l.left, nr);
                } else {
                    TreeNode lr = l.right;
                    TreeNode nl = new TreeNode(l.key, l.value, l.left, lr.left);
                    TreeNode nr = new TreeNode(k, v, lr.right, r);
                    return new TreeNode(lr.key, lr.value, nl, nr);
                }
            }
            if (bf < -1) {
                if (TreeNode.height(r.right) >= TreeNode.height(r.left)) {
                    TreeNode nl = new TreeNode(k, v, l, r.left);
                    return new TreeNode(r.key, r.value, nl, r.right);
                } else {
                    TreeNode rl = r.left;
                    TreeNode nl = new TreeNode(k, v, l, rl.left);
                    TreeNode nr = new TreeNode(r.key, r.value, rl.right, r.right);
                    return new TreeNode(rl.key, rl.value, nl, nr);
                }
            }
            return new TreeNode(k, v, l, r);
        }
    }
}
