import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WalRecovery {
    private WalRecovery() {}

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

    private static final byte[] MAGIC = {0x57, 0x41, 0x4C, 0x31};

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        Objects.requireNonNull(log);
        final int L = log.length;

        // R2
        if (L == 0) {
            return emptyResult(0, false);
        }
        // R3
        if (L < 4) {
            for (int i = 0; i < L; i++) {
                if (log[i] != MAGIC[i]) {
                    throw new WalException(Code.BAD_MAGIC, 0);
                }
            }
            return emptyResult(0, true);
        }
        // R4
        if (log[0] != MAGIC[0] || log[1] != MAGIC[1] || log[2] != MAGIC[2] || log[3] != MAGIC[3]) {
            throw new WalException(Code.BAD_MAGIC, 0);
        }

        // Phase 1: frames
        int p = 4;
        int validLength = L;
        boolean tornTail = false;

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
            int N = u16(log, p + 1);
            if (N > MAX_PAYLOAD) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }
            // R9
            int nextP = p + 5 + N;
            if (nextP > L) {
                validLength = p;
                tornTail = true;
                break;
            }
            // R10
            if (!checkFletcher16(log, p, 3 + N, log[p + 3 + N] & 0xFF, log[p + 4 + N] & 0xFF)) {
                if (nextP == L) {
                    validLength = p;
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }
            // R11
            p = nextP;
        }

        // Phase 2: replay
        IntSet seenTxns = new IntSet(1024);
        PersistentTreap currentState = new PersistentTreap();
        Txn[] openTxns = new Txn[MAX_OPEN_TXNS];
        int openCount = 0;

        Txn[] txnPool = new Txn[MAX_OPEN_TXNS];
        for (int i = 0; i < MAX_OPEN_TXNS; i++) {
            txnPool[i] = new Txn();
        }
        int poolTop = MAX_OPEN_TXNS;

        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();

        p = 4;
        while (p < validLength) {
            int type = log[p] & 0xFF;
            int N = u16(log, p + 1);
            int payloadOff = p + 3;

            switch (type) {
                case 1 -> { // BEGIN
                    // R13
                    if (N != 4) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    // R14
                    int txnId = s32(log, payloadOff);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, p);
                    // R17
                    if (!seenTxns.add(txnId)) throw new WalException(Code.DUPLICATE_TXN, p);
                    // R18
                    if (openCount == MAX_OPEN_TXNS) throw new WalException(Code.TOO_MANY_OPEN, p);

                    // R20
                    Txn t = txnPool[--poolTop];
                    t.init(txnId, p, currentState.root);
                    openTxns[openCount++] = t;
                }
                case 2 -> { // PUT
                    // R13
                    if (N < 5) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int k = log[payloadOff + 4] & 0xFF;
                    if (N < 7 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int v = u16(log, payloadOff + 5 + k);
                    if (N != 7 + k + v) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    // R14
                    int txnId = s32(log, payloadOff);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, p);
                    // R15
                    checkKey(log, payloadOff + 5, k, p);
                    // R16
                    int valOff = payloadOff + 7 + k;
                    checkValue(log, valOff, v, p);
                    // R19
                    Txn t = findOpenTxn(openTxns, openCount, txnId);
                    if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);
                    // R19a
                    if (t.writeCount == MAX_WRITES) throw new WalException(Code.TXN_TOO_LARGE, p);

                    // R21
                    String key = asciiString(log, payloadOff + 5, k);
                    t.addPut(key, valOff, v);
                }
                case 3 -> { // DELETE
                    // R13
                    if (N < 5) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int k = log[payloadOff + 4] & 0xFF;
                    if (N != 5 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    // R14
                    int txnId = s32(log, payloadOff);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, p);
                    // R15
                    checkKey(log, payloadOff + 5, k, p);
                    // R19
                    Txn t = findOpenTxn(openTxns, openCount, txnId);
                    if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);
                    // R19a
                    if (t.writeCount == MAX_WRITES) throw new WalException(Code.TXN_TOO_LARGE, p);

                    // R21
                    String key = asciiString(log, payloadOff + 5, k);
                    t.addDelete(key);
                }
                case 4 -> { // COMMIT
                    // R13
                    if (N != 4) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    // R14
                    int txnId = s32(log, payloadOff);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, p);
                    // R19
                    int idx = findOpenTxnIndex(openTxns, openCount, txnId);
                    if (idx < 0) throw new WalException(Code.TXN_NOT_OPEN, p);

                    // R22
                    Txn t = openTxns[idx];
                    openTxns[idx] = openTxns[--openCount];
                    openTxns[openCount] = null;

                    if (t.conflicted) {
                        aborted.add(Integer.toUnsignedLong(txnId));
                    } else {
                        // Notify remaining open transactions that began before this COMMIT
                        if (openCount > 0 && t.writeCount > 0) {
                            notifyCommittedWrites(openTxns, openCount, t);
                        }
                        // Apply writes to state in write-list order
                        for (int i = 0; i < t.writeCount; i++) {
                            String wKey = t.writeKeys[i];
                            int vOff = t.writeValOff[i];
                            if (vOff >= 0) {
                                String val = asciiString(log, vOff, t.writeValLen[i]);
                                currentState.put(wKey, val);
                            } else {
                                currentState.remove(wKey);
                            }
                        }
                        committed.add(Integer.toUnsignedLong(txnId));
                    }
                    t.reset();
                    txnPool[poolTop++] = t;
                }
                case 5 -> { // ABORT
                    // R13
                    if (N != 4) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    // R14
                    int txnId = s32(log, payloadOff);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, p);
                    // R19
                    int idx = findOpenTxnIndex(openTxns, openCount, txnId);
                    if (idx < 0) throw new WalException(Code.TXN_NOT_OPEN, p);

                    // R23
                    Txn t = openTxns[idx];
                    openTxns[idx] = openTxns[--openCount];
                    openTxns[openCount] = null;

                    aborted.add(Integer.toUnsignedLong(txnId));
                    t.reset();
                    txnPool[poolTop++] = t;
                }
                case 6 -> { // SAVEPOINT
                    // R13
                    if (N != 5) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    // R14
                    int txnId = s32(log, payloadOff);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, p);
                    // R16a
                    int name = log[payloadOff + 4] & 0xFF;
                    if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) {
                        throw new WalException(Code.BAD_NAME, p);
                    }
                    // R19
                    Txn t = findOpenTxn(openTxns, openCount, txnId);
                    if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);

                    // R23a
                    t.setSavepoint(name, p);
                }
                case 7 -> { // ROLLBACK
                    // R13
                    if (N != 5) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    // R14
                    int txnId = s32(log, payloadOff);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, p);
                    // R16a
                    int name = log[payloadOff + 4] & 0xFF;
                    if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) {
                        throw new WalException(Code.BAD_NAME, p);
                    }
                    // R19
                    Txn t = findOpenTxn(openTxns, openCount, txnId);
                    if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);
                    // R19b
                    if (!t.hasSavepoint(name)) {
                        throw new WalException(Code.NO_SAVEPOINT, p);
                    }

                    // R23b
                    t.rollbackTo(name);
                }
                case 8 -> { // CHECKPOINT
                    // R13
                    if (N < 1) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int c = log[payloadOff] & 0xFF;
                    if (N != 1 + 4 * c) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    // R14
                    for (int i = 0; i < c; i++) {
                        int listedId = s32(log, payloadOff + 1 + 4 * i);
                        if (listedId == 0) throw new WalException(Code.BAD_TXN_ID, p);
                    }
                    // R19c
                    if (c != openCount) {
                        throw new WalException(Code.CHECKPOINT_MISMATCH, p);
                    }
                    if (c > 0) {
                        long[] openIds = new long[openCount];
                        for (int i = 0; i < openCount; i++) {
                            openIds[i] = Integer.toUnsignedLong(openTxns[i].id);
                        }
                        Arrays.sort(openIds);
                        for (int i = 0; i < c; i++) {
                            long listed = Integer.toUnsignedLong(s32(log, payloadOff + 1 + 4 * i));
                            if (listed != openIds[i]) {
                                throw new WalException(Code.CHECKPOINT_MISMATCH, p);
                            }
                        }
                    }
                    // R23c: nothing changes
                }
                case 9 -> { // READ
                    // R13
                    if (N < 6) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int k = log[payloadOff + 4] & 0xFF;
                    if (N < 6 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int f = log[payloadOff + 5 + k] & 0xFF;
                    int v = 0;
                    int expValOff = 0;
                    if (f == 0) {
                        if (N != 6 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    } else if (f == 1) {
                        if (N < 8 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                        v = u16(log, payloadOff + 6 + k);
                        if (N != 8 + k + v) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                        expValOff = payloadOff + 8 + k;
                    } else {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                    // R14
                    int txnId = s32(log, payloadOff);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, p);
                    // R15
                    checkKey(log, payloadOff + 5, k, p);
                    // R16
                    if (f == 1) {
                        checkValue(log, expValOff, v, p);
                    }
                    // R19
                    Txn t = findOpenTxn(openTxns, openCount, txnId);
                    if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);

                    // R19d
                    String key = asciiString(log, payloadOff + 5, k);
                    if (!checkReadView(log, t, key, f, expValOff, v)) {
                        throw new WalException(Code.READ_MISMATCH, p);
                    }

                    // R23d
                    t.addReadKey(key);
                }
                case 10 -> { // RANGE
                    // R13
                    if (N < 6) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int k1 = log[payloadOff + 4] & 0xFF;
                    if (N < 6 + k1) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int k2 = log[payloadOff + 5 + k1] & 0xFF;
                    if (N != 10 + k1 + k2) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    // R14
                    int txnId = s32(log, payloadOff);
                    if (txnId == 0) throw new WalException(Code.BAD_TXN_ID, p);
                    // R15
                    checkKey(log, payloadOff + 5, k1, p);
                    checkKey(log, payloadOff + 6 + k1, k2, p);
                    // R19
                    Txn t = findOpenTxn(openTxns, openCount, txnId);
                    if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);

                    // R19e
                    String lo = asciiString(log, payloadOff + 5, k1);
                    String hi = asciiString(log, payloadOff + 6 + k1, k2);
                    long expectedCount = Integer.toUnsignedLong(s32(log, payloadOff + 6 + k1 + k2));
                    int actualCount = countRangeView(t, lo, hi);
                    if ((long) actualCount != expectedCount) {
                        throw new WalException(Code.RANGE_MISMATCH, p);
                    }

                    // R23e
                    t.addReadRange(lo, hi);
                }
            }

            p += 5 + N;
        }

        // Build result
        Map<String, String> stateMap = new HashMap<>();
        PersistentTreap.collect(currentState.root, stateMap);

        List<Long> incomplete = new ArrayList<>(openCount);
        if (openCount > 0) {
            long[] incArr = new long[openCount];
            for (int i = 0; i < openCount; i++) {
                incArr[i] = Integer.toUnsignedLong(openTxns[i].id);
            }
            Arrays.sort(incArr);
            for (long id : incArr) {
                incomplete.add(id);
            }
        }

        return new Result(stateMap, committed, aborted, incomplete, validLength, tornTail);
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

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int s32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static String asciiString(byte[] b, int off, int len) {
        if (len == 0) return "";
        return new String(b, off, len, StandardCharsets.ISO_8859_1);
    }

    private static boolean checkFletcher16(byte[] log, int off, int len, int expectedS2, int expectedS1) {
        int s1 = 0;
        int s2 = 0;
        int end = off + len;
        // Max len is 3 + 1024 = 1027. Max unreduced s2 < 1027 * 1028 / 2 * 255 < 1.35e8 < Integer.MAX_VALUE.
        for (int i = off; i < end; i++) {
            s1 += log[i] & 0xFF;
            s2 += s1;
        }
        s1 %= 255;
        s2 %= 255;
        return s2 == expectedS2 && s1 == expectedS1;
    }

    private static void checkKey(byte[] log, int off, int len, int recordOffset) throws WalException {
        if (len < MIN_KEY_LEN || len > MAX_KEY_LEN) {
            throw new WalException(Code.BAD_KEY, recordOffset);
        }
        int end = off + len;
        for (int i = off; i < end; i++) {
            int c = log[i] & 0xFF;
            if (c < KEY_BYTE_MIN || c > KEY_BYTE_MAX) {
                throw new WalException(Code.BAD_KEY, recordOffset);
            }
        }
    }

    private static void checkValue(byte[] log, int off, int len, int recordOffset) throws WalException {
        int end = off + len;
        for (int i = off; i < end; i++) {
            int c = log[i] & 0xFF;
            if (c < VALUE_BYTE_MIN || c > VALUE_BYTE_MAX) {
                throw new WalException(Code.BAD_VALUE, recordOffset);
            }
        }
    }

    private static Txn findOpenTxn(Txn[] openTxns, int openCount, int txnId) {
        for (int i = 0; i < openCount; i++) {
            if (openTxns[i].id == txnId) return openTxns[i];
        }
        return null;
    }

    private static int findOpenTxnIndex(Txn[] openTxns, int openCount, int txnId) {
        for (int i = 0; i < openCount; i++) {
            if (openTxns[i].id == txnId) return i;
        }
        return -1;
    }

    private static boolean checkReadView(byte[] log, Txn t, String key, int f, int expValOff, int expValLen) {
        // Check write list from last to first
        for (int i = t.writeCount - 1; i >= 0; i--) {
            if (t.writeKeys[i].equals(key)) {
                int wValOff = t.writeValOff[i];
                if (wValOff < 0) { // DELETE
                    return f == 0;
                } else { // PUT
                    if (f == 0) return false;
                    int wValLen = t.writeValLen[i];
                    if (wValLen != expValLen) return false;
                    return Arrays.equals(log, wValOff, wValOff + wValLen, log, expValOff, expValOff + expValLen);
                }
            }
        }
        // Check snapshot
        String snapVal = PersistentTreap.get(t.snapshotRoot, key);
        if (snapVal == null) {
            return f == 0;
        } else {
            if (f == 0) return false;
            if (snapVal.length() != expValLen) return false;
            for (int i = 0; i < expValLen; i++) {
                if (snapVal.charAt(i) != (log[expValOff + i] & 0xFF)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static int countRangeView(Txn t, String lo, String hi) {
        if (lo.compareTo(hi) > 0) {
            return 0;
        }
        int count = PersistentTreap.countRange(t.snapshotRoot, lo, hi);
        if (t.writeCount == 0) {
            return count;
        }
        // Apply unique writes in [lo, hi], taking the last write per key
        for (int i = t.writeCount - 1; i >= 0; i--) {
            String k = t.writeKeys[i];
            if (k.compareTo(lo) < 0 || k.compareTo(hi) > 0) {
                continue;
            }
            boolean seenLater = false;
            for (int j = i + 1; j < t.writeCount; j++) {
                if (t.writeKeys[j].equals(k)) {
                    seenLater = true;
                    break;
                }
            }
            if (seenLater) {
                continue;
            }
            boolean inSnap = PersistentTreap.get(t.snapshotRoot, k) != null;
            boolean isPut = t.writeValOff[i] >= 0;
            if (isPut && !inSnap) {
                count++;
            } else if (!isPut && inSnap) {
                count--;
            }
        }
        return count;
    }

    private static void notifyCommittedWrites(Txn[] openTxns, int openCount, Txn committedTxn) {
        // Every open transaction in openTxns had its BEGIN processed before this COMMIT record,
        // so its BEGIN offset is strictly smaller than this COMMIT's offset.
        int wCount = committedTxn.writeCount;
        for (int i = 0; i < openCount; i++) {
            Txn other = openTxns[i];
            if (other.conflicted) continue;
            boolean hasReads = other.readSet != null && !other.readSet.isEmpty();
            boolean hasRanges = other.rangeCount > 0;
            if (!hasReads && !hasRanges) continue;

            for (int w = 0; w < wCount; w++) {
                String wKey = committedTxn.writeKeys[w];
                // Dedup against earlier writes in committedTxn
                boolean duplicate = false;
                for (int prev = 0; prev < w; prev++) {
                    if (committedTxn.writeKeys[prev].equals(wKey)) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;

                if (hasReads && other.readSet.contains(wKey)) {
                    other.conflicted = true;
                    break;
                }
                if (hasRanges && other.matchesRange(wKey)) {
                    other.conflicted = true;
                    break;
                }
            }
        }
    }

    private static final class Txn {
        int id;
        int beginOffset;
        TreapNode snapshotRoot;

        final String[] writeKeys = new String[MAX_WRITES];
        final int[] writeValOff = new int[MAX_WRITES]; // -1 for DELETE
        final int[] writeValLen = new int[MAX_WRITES];
        int writeCount;

        // Savepoints indexed by byte value (0x21..0x7E)
        final int[] spPos = new int[128];
        final int[] spRecordOffset = new int[128];
        final int[] activeSpNames = new int[128];
        int activeSpCount;

        HashSet<String> readSet;
        String[] rangeLo;
        String[] rangeHi;
        int rangeCount;
        boolean rangesDirty;
        String[] mergedLo;
        String[] mergedHi;
        int mergedCount;

        boolean conflicted;

        Txn() {
            Arrays.fill(spPos, -1);
        }

        void init(int id, int beginOffset, TreapNode snapshotRoot) {
            this.id = id;
            this.beginOffset = beginOffset;
            this.snapshotRoot = snapshotRoot;
            this.writeCount = 0;
            this.activeSpCount = 0;
            this.conflicted = false;
            this.rangeCount = 0;
            this.rangesDirty = false;
            this.mergedCount = 0;
        }

        void reset() {
            this.snapshotRoot = null;
            Arrays.fill(writeKeys, 0, writeCount, null);
            this.writeCount = 0;
            for (int i = 0; i < activeSpCount; i++) {
                int name = activeSpNames[i];
                spPos[name] = -1;
                spRecordOffset[name] = 0;
            }
            this.activeSpCount = 0;
            if (readSet != null) {
                readSet.clear();
            }
            if (rangeLo != null && rangeCount > 0) {
                Arrays.fill(rangeLo, 0, rangeCount, null);
                Arrays.fill(rangeHi, 0, rangeCount, null);
            }
            if (mergedLo != null && mergedCount > 0) {
                Arrays.fill(mergedLo, 0, mergedCount, null);
                Arrays.fill(mergedHi, 0, mergedCount, null);
            }
            this.rangeCount = 0;
            this.mergedCount = 0;
            this.rangesDirty = false;
            this.conflicted = false;
        }

        void addPut(String key, int valOff, int valLen) {
            int idx = writeCount++;
            writeKeys[idx] = key;
            writeValOff[idx] = valOff;
            writeValLen[idx] = valLen;
        }

        void addDelete(String key) {
            int idx = writeCount++;
            writeKeys[idx] = key;
            writeValOff[idx] = -1;
            writeValLen[idx] = 0;
        }

        void setSavepoint(int name, int recordOffset) {
            if (spPos[name] == -1) {
                activeSpNames[activeSpCount++] = name;
            }
            spPos[name] = writeCount;
            spRecordOffset[name] = recordOffset;
        }

        boolean hasSavepoint(int name) {
            return spPos[name] != -1;
        }

        void rollbackTo(int name) {
            int targetPos = spPos[name];
            int targetOffset = spRecordOffset[name];
            for (int i = targetPos; i < writeCount; i++) {
                writeKeys[i] = null;
            }
            writeCount = targetPos;

            int newActive = 0;
            for (int i = 0; i < activeSpCount; i++) {
                int spName = activeSpNames[i];
                if (spName != name && spRecordOffset[spName] > targetOffset) {
                    spPos[spName] = -1;
                    spRecordOffset[spName] = 0;
                } else {
                    activeSpNames[newActive++] = spName;
                }
            }
            activeSpCount = newActive;
        }

        void addReadKey(String key) {
            if (conflicted) return;
            if (readSet == null) {
                readSet = new HashSet<>();
            }
            readSet.add(key);
        }

        void addReadRange(String lo, String hi) {
            if (conflicted) return;
            if (lo.compareTo(hi) > 0) {
                // Empty range can never contain any key K
                return;
            }
            if (rangeLo == null) {
                rangeLo = new String[8];
                rangeHi = new String[8];
            } else if (rangeCount == rangeLo.length) {
                rangeLo = Arrays.copyOf(rangeLo, rangeCount * 2);
                rangeHi = Arrays.copyOf(rangeHi, rangeCount * 2);
            }
            rangeLo[rangeCount] = lo;
            rangeHi[rangeCount] = hi;
            rangeCount++;
            rangesDirty = true;
        }

        boolean matchesRange(String key) {
            if (rangeCount == 0) return false;
            if (rangeCount <= 8 && !rangesDirty && mergedCount > 0) {
                return binarySearchMerged(key);
            }
            if (rangeCount <= 8) {
                for (int i = 0; i < rangeCount; i++) {
                    if (key.compareTo(rangeLo[i]) >= 0 && key.compareTo(rangeHi[i]) <= 0) {
                        return true;
                    }
                }
                return false;
            }
            if (rangesDirty) {
                rebuildMergedRanges();
            }
            return binarySearchMerged(key);
        }

        private void rebuildMergedRanges() {
            int n = rangeCount;
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> {
                int c = rangeLo[a].compareTo(rangeLo[b]);
                return c != 0 ? c : rangeHi[a].compareTo(rangeHi[b]);
            });

            if (mergedLo == null || mergedLo.length < n) {
                mergedLo = new String[n];
                mergedHi = new String[n];
            }
            int m = 0;
            String curLo = rangeLo[order[0]];
            String curHi = rangeHi[order[0]];
            for (int i = 1; i < n; i++) {
                String nextLo = rangeLo[order[i]];
                String nextHi = rangeHi[order[i]];
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
            mergedCount = m + 1;
            rangesDirty = false;
        }

        private boolean binarySearchMerged(String key) {
            int low = 0;
            int high = mergedCount - 1;
            int candidate = -1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (mergedLo[mid].compareTo(key) <= 0) {
                    candidate = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return candidate >= 0 && key.compareTo(mergedHi[candidate]) <= 0;
        }
    }

    private static final class TreapNode {
        final String key;
        final String value;
        final int priority;
        final TreapNode left;
        final TreapNode right;
        final int size;

        TreapNode(String key, String value, int priority, TreapNode left, TreapNode right) {
            this.key = key;
            this.value = value;
            this.priority = priority;
            this.left = left;
            this.right = right;
            this.size = 1 + size(left) + size(right);
        }

        static int size(TreapNode n) {
            return n == null ? 0 : n.size;
        }
    }

    private static final class PersistentTreap {
        TreapNode root;
        private long rngState = 0x9E3779B97F4A7C15L;

        private int nextPriority() {
            long z = (rngState += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return (int) (z ^ (z >>> 31));
        }

        void put(String key, String value) {
            root = insertOrReplace(root, key, value);
        }

        void remove(String key) {
            root = delete(root, key);
        }

        private TreapNode insertOrReplace(TreapNode node, String key, String value) {
            if (node == null) {
                return new TreapNode(key, value, nextPriority(), null, null);
            }
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                if (node.value.equals(value)) {
                    return node;
                }
                return new TreapNode(key, value, node.priority, node.left, node.right);
            } else if (cmp < 0) {
                TreapNode newLeft = insertOrReplace(node.left, key, value);
                if (newLeft == node.left) return node;
                if (newLeft.priority > node.priority) {
                    // Rotate right
                    TreapNode newRight = new TreapNode(node.key, node.value, node.priority, newLeft.right, node.right);
                    return new TreapNode(newLeft.key, newLeft.value, newLeft.priority, newLeft.left, newRight);
                }
                return new TreapNode(node.key, node.value, node.priority, newLeft, node.right);
            } else {
                TreapNode newRight = insertOrReplace(node.right, key, value);
                if (newRight == node.right) return node;
                if (newRight.priority > node.priority) {
                    // Rotate left
                    TreapNode newLeft = new TreapNode(node.key, node.value, node.priority, node.left, newRight.left);
                    return new TreapNode(newRight.key, newRight.value, newRight.priority, newLeft, newRight.right);
                }
                return new TreapNode(node.key, node.value, node.priority, node.left, newRight);
            }
        }

        private static TreapNode delete(TreapNode node, String key) {
            if (node == null) {
                return null;
            }
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                return merge(node.left, node.right);
            } else if (cmp < 0) {
                TreapNode newLeft = delete(node.left, key);
                if (newLeft == node.left) return node;
                return new TreapNode(node.key, node.value, node.priority, newLeft, node.right);
            } else {
                TreapNode newRight = delete(node.right, key);
                if (newRight == node.right) return node;
                return new TreapNode(node.key, node.value, node.priority, node.left, newRight);
            }
        }

        private static TreapNode merge(TreapNode left, TreapNode right) {
            if (left == null) return right;
            if (right == null) return left;
            if (left.priority > right.priority) {
                TreapNode mergedRight = merge(left.right, right);
                return new TreapNode(left.key, left.value, left.priority, left.left, mergedRight);
            } else {
                TreapNode mergedLeft = merge(left, right.left);
                return new TreapNode(right.key, right.value, right.priority, mergedLeft, right.right);
            }
        }

        static String get(TreapNode node, String key) {
            while (node != null) {
                int cmp = key.compareTo(node.key);
                if (cmp == 0) return node.value;
                node = cmp < 0 ? node.left : node.right;
            }
            return null;
        }

        static int countRange(TreapNode root, String lo, String hi) {
            if (root == null) return 0;
            return countLessOrEqual(root, hi) - countStrictlyLess(root, lo);
        }

        private static int countStrictlyLess(TreapNode node, String key) {
            int count = 0;
            while (node != null) {
                int cmp = node.key.compareTo(key);
                if (cmp < 0) {
                    count += 1 + TreapNode.size(node.left);
                    node = node.right;
                } else {
                    node = node.left;
                }
            }
            return count;
        }

        private static int countLessOrEqual(TreapNode node, String key) {
            int count = 0;
            while (node != null) {
                int cmp = node.key.compareTo(key);
                if (cmp <= 0) {
                    count += 1 + TreapNode.size(node.left);
                    node = node.right;
                } else {
                    node = node.left;
                }
            }
            return count;
        }

        static void collect(TreapNode node, Map<String, String> out) {
            if (node == null) return;
            collect(node.left, out);
            out.put(node.key, node.value);
            collect(node.right, out);
        }
    }

    private static final class IntSet {
        private int[] table;
        private int mask;
        private int size;
        private int threshold;

        IntSet(int initialCapacity) {
            int cap = Integer.highestOneBit(Math.max(16, initialCapacity - 1)) << 1;
            table = new int[cap];
            mask = cap - 1;
            threshold = (cap * 7) >>> 3;
        }

        boolean add(int key) {
            // key != 0 is guaranteed by R14 before R17
            int[] tab = table;
            int m = mask;
            int idx = mix(key) & m;
            while (true) {
                int cur = tab[idx];
                if (cur == 0) {
                    tab[idx] = key;
                    if (++size >= threshold) {
                        rehash();
                    }
                    return true;
                }
                if (cur == key) {
                    return false;
                }
                idx = (idx + 1) & m;
            }
        }

        private void rehash() {
            int[] oldTab = table;
            int newCap = oldTab.length << 1;
            int[] newTab = new int[newCap];
            int newMask = newCap - 1;
            for (int k : oldTab) {
                if (k != 0) {
                    int idx = mix(k) & newMask;
                    while (newTab[idx] != 0) {
                        idx = (idx + 1) & newMask;
                    }
                    newTab[idx] = k;
                }
            }
            table = newTab;
            mask = newMask;
            threshold = (newCap * 7) >>> 3;
        }

        private static int mix(int x) {
            x ^= x >>> 16;
            x *= 0x7feb352d;
            x ^= x >>> 15;
            x *= 0x846ca68b;
            x ^= x >>> 16;
            return x;
        }
    }
}
