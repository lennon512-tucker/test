import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class WalRecovery {
    private WalRecovery() {}

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

    public record Result(Map<String, String> state,
                         List<Long> committed,
                         List<Long> aborted,
                         List<Long> incomplete,
                         int validLength,
                         boolean tornTail) {}

    private static final byte[] MAGIC = { 0x57, 0x41, 0x4C, 0x31 };
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
        if (log == null) {
            throw new NullPointerException("log must not be null");
        }
        int L = log.length;
        if (L == 0) {
            return new Result(Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, false);
        }
        if (L < HEADER_SIZE) {
            for (int i = 0; i < L; i++) {
                if (log[i] != MAGIC[i]) {
                    throw new WalException(Code.BAD_MAGIC, 0);
                }
            }
            return new Result(Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, true);
        }
        for (int i = 0; i < HEADER_SIZE; i++) {
            if (log[i] != MAGIC[i]) {
                throw new WalException(Code.BAD_MAGIC, 0);
            }
        }

        // Phase 1: frames
        IntList intactOffsets = new IntList(Math.min(1048576, (L / 9) + 16));
        int p = HEADER_SIZE;
        int validLength;
        boolean tornTail;

        while (true) {
            if (p == L) {
                validLength = L;
                tornTail = false;
                break;
            }
            if (L - p <= 2) {
                validLength = p;
                tornTail = true;
                break;
            }
            int type = log[p] & 0xFF;
            if (type < 1 || type > 10) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }
            int N = readU16(log, p + 1);
            if (N > MAX_PAYLOAD) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }
            int end = p + 5 + N;
            if (end > L) {
                validLength = p;
                tornTail = true;
                break;
            }
            if (!checkFletcher16(log, p, 3 + N, log[p + 3 + N] & 0xFF, log[p + 4 + N] & 0xFF)) {
                if (end == L) {
                    validLength = p;
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }
            intactOffsets.add(p);
            p = end;
        }

        // Phase 2: replay
        PersistentTreap state = new PersistentTreap();
        LongHashSet seenTxns = new LongHashSet(1024);
        Txn[] openTxns = new Txn[MAX_OPEN_TXNS];
        int openCount = 0;

        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();

        int numRecords = intactOffsets.size();
        for (int idx = 0; idx < numRecords; idx++) {
            int recOffset = intactOffsets.get(idx);
            int type = log[recOffset] & 0xFF;
            int N = readU16(log, recOffset + 1);
            int pb = recOffset + 3; // payload base

            // R13: Payload layout
            int k = 0, v = 0, f = 0, k1 = 0, k2 = 0, c = 0;
            switch (type) {
                case 1: // BEGIN
                case 4: // COMMIT
                case 5: // ABORT
                    if (N != 4) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    break;
                case 2: // PUT
                    if (N < 5) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    k = log[pb + 4] & 0xFF;
                    if (N < 7 + k) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    v = readU16(log, pb + 5 + k);
                    if (N != 7 + k + v) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    break;
                case 3: // DELETE
                    if (N < 5) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    k = log[pb + 4] & 0xFF;
                    if (N != 5 + k) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    break;
                case 6: // SAVEPOINT
                case 7: // ROLLBACK
                    if (N != 5) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    break;
                case 8: // CHECKPOINT
                    if (N < 1) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    c = log[pb] & 0xFF;
                    if (N != 1 + 4 * c) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    break;
                case 9: // READ
                    if (N < 6) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    k = log[pb + 4] & 0xFF;
                    if (N < 6 + k) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    f = log[pb + 5 + k] & 0xFF;
                    if (f == 0) {
                        if (N != 6 + k) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    } else if (f == 1) {
                        if (N < 8 + k) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                        v = readU16(log, pb + 6 + k);
                        if (N != 8 + k + v) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    } else {
                        throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    }
                    break;
                case 10: // RANGE
                    if (N < 6) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    k1 = log[pb + 4] & 0xFF;
                    if (N < 6 + k1) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    k2 = log[pb + 5 + k1] & 0xFF;
                    if (N != 10 + k1 + k2) throw new WalException(Code.MALFORMED_PAYLOAD, recOffset);
                    break;
            }

            // R14: Txn ids
            long txnId = 0;
            if (type == 8) {
                for (int i = 0; i < c; i++) {
                    long listedId = readU32(log, pb + 1 + 4 * i);
                    if (listedId == 0) {
                        throw new WalException(Code.BAD_TXN_ID, recOffset);
                    }
                }
            } else {
                txnId = readU32(log, pb);
                if (txnId == 0) {
                    throw new WalException(Code.BAD_TXN_ID, recOffset);
                }
            }

            // R15: Key
            if (type == 2 || type == 3 || type == 9) {
                checkKey(log, pb + 5, k, recOffset);
            } else if (type == 10) {
                checkKey(log, pb + 5, k1, recOffset);
                checkKey(log, pb + 6 + k1, k2, recOffset);
            }

            // R16: Value
            if (type == 2) {
                checkValue(log, pb + 7 + k, v, recOffset);
            } else if (type == 9 && f == 1) {
                checkValue(log, pb + 8 + k, v, recOffset);
            }

            // R16a: Name
            int spName = 0;
            if (type == 6 || type == 7) {
                spName = log[pb + 4] & 0xFF;
                if (spName < NAME_BYTE_MIN || spName > NAME_BYTE_MAX) {
                    throw new WalException(Code.BAD_NAME, recOffset);
                }
            }

            // R17 & R18: BEGIN transaction checks & R20 action
            if (type == 1) {
                if (seenTxns.contains(txnId)) {
                    throw new WalException(Code.DUPLICATE_TXN, recOffset);
                }
                if (openCount == MAX_OPEN_TXNS) {
                    throw new WalException(Code.TOO_MANY_OPEN, recOffset);
                }
                seenTxns.add(txnId);
                openTxns[openCount++] = new Txn(txnId, state.root);
                continue;
            }

            // R19c: CHECKPOINT check & R23c action
            if (type == 8) {
                if (c != openCount) {
                    throw new WalException(Code.CHECKPOINT_MISMATCH, recOffset);
                }
                if (openCount > 0) {
                    long[] sortedOpen = new long[openCount];
                    for (int i = 0; i < openCount; i++) {
                        sortedOpen[i] = openTxns[i].id;
                    }
                    Arrays.sort(sortedOpen);
                    for (int i = 0; i < c; i++) {
                        long listedId = readU32(log, pb + 1 + 4 * i);
                        if (listedId != sortedOpen[i]) {
                            throw new WalException(Code.CHECKPOINT_MISMATCH, recOffset);
                        }
                    }
                }
                continue;
            }

            // R19: Open check for PUT, DELETE, COMMIT, ABORT, SAVEPOINT, ROLLBACK, READ, RANGE
            int txnIdx = -1;
            for (int i = 0; i < openCount; i++) {
                if (openTxns[i].id == txnId) {
                    txnIdx = i;
                    break;
                }
            }
            if (txnIdx == -1) {
                throw new WalException(Code.TXN_NOT_OPEN, recOffset);
            }
            Txn txn = openTxns[txnIdx];

            switch (type) {
                case 2: { // PUT
                    // R19a
                    if (txn.writeCount == MAX_WRITES) {
                        throw new WalException(Code.TXN_TOO_LARGE, recOffset);
                    }
                    // R21
                    String key = asciiString(log, pb + 5, k);
                    int valOff = pb + 7 + k;
                    txn.addPut(key, valOff, v);
                    break;
                }
                case 3: { // DELETE
                    // R19a
                    if (txn.writeCount == MAX_WRITES) {
                        throw new WalException(Code.TXN_TOO_LARGE, recOffset);
                    }
                    // R21
                    String key = asciiString(log, pb + 5, k);
                    txn.addDelete(key);
                    break;
                }
                case 4: { // COMMIT
                    // R22
                    // Remove txn from openTxns
                    openTxns[txnIdx] = openTxns[--openCount];
                    openTxns[openCount] = null;

                    if (txn.conflicted) {
                        aborted.add(txn.id);
                    } else {
                        for (int w = 0; w < txn.writeCount; w++) {
                            String wKey = txn.writeKeys[w];
                            int wValOff = txn.writeValOffsets[w];
                            if (wValOff >= 0) {
                                String wVal = asciiString(log, wValOff, txn.writeValLengths[w]);
                                state.put(wKey, wVal);
                            } else {
                                state.remove(wKey);
                            }
                        }
                        committed.add(txn.id);

                        // Notify remaining open transactions of written keys
                        if (openCount > 0 && txn.writeCount > 0) {
                            for (int o = 0; o < openCount; o++) {
                                Txn other = openTxns[o];
                                if (!other.conflicted && other.hasReadsOrRanges()) {
                                    for (int w = 0; w < txn.writeCount; w++) {
                                        if (other.conflictsWith(txn.writeKeys[w])) {
                                            other.conflicted = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    break;
                }
                case 5: { // ABORT
                    // R23
                    openTxns[txnIdx] = openTxns[--openCount];
                    openTxns[openCount] = null;
                    aborted.add(txn.id);
                    break;
                }
                case 6: { // SAVEPOINT
                    // R23a
                    txn.setSavepoint(spName, recOffset);
                    break;
                }
                case 7: { // ROLLBACK
                    // R19b
                    if (!txn.hasSavepoint(spName)) {
                        throw new WalException(Code.NO_SAVEPOINT, recOffset);
                    }
                    // R23b
                    txn.rollbackTo(spName);
                    break;
                }
                case 9: { // READ
                    // R19d
                    String key = asciiString(log, pb + 5, k);
                    int writeIdx = txn.findLastWrite(key);
                    if (writeIdx >= 0) {
                        int wValOff = txn.writeValOffsets[writeIdx];
                        if (wValOff < 0) {
                            // View is absent
                            if (f != 0) {
                                throw new WalException(Code.READ_MISMATCH, recOffset);
                            }
                        } else {
                            // View is present with value at wValOff, length wValLen
                            if (f != 1) {
                                throw new WalException(Code.READ_MISMATCH, recOffset);
                            }
                            int wValLen = txn.writeValLengths[writeIdx];
                            if (wValLen != v || !bytesEqual(log, wValOff, pb + 8 + k, v)) {
                                throw new WalException(Code.READ_MISMATCH, recOffset);
                            }
                        }
                    } else {
                        String snapVal = PersistentTreap.get(txn.snapshotRoot, key);
                        if (snapVal == null) {
                            if (f != 0) {
                                throw new WalException(Code.READ_MISMATCH, recOffset);
                            }
                        } else {
                            if (f != 1) {
                                throw new WalException(Code.READ_MISMATCH, recOffset);
                            }
                            if (!stringEqualsBytes(snapVal, log, pb + 8 + k, v)) {
                                throw new WalException(Code.READ_MISMATCH, recOffset);
                            }
                        }
                    }
                    // R23d
                    txn.addRead(key);
                    break;
                }
                case 10: { // RANGE
                    // R19e
                    String lo = asciiString(log, pb + 5, k1);
                    String hi = asciiString(log, pb + 6 + k1, k2);
                    long expectedCount = readU32(log, pb + 6 + k1 + k2);
                    long actualCount = 0;
                    if (lo.compareTo(hi) <= 0) {
                        actualCount = PersistentTreap.countRange(txn.snapshotRoot, lo, hi);
                        // Adjust for distinct keys in write list that fall in [lo, hi]
                        for (int w = txn.writeCount - 1; w >= 0; w--) {
                            String wKey = txn.writeKeys[w];
                            boolean seenLater = false;
                            for (int later = w + 1; later < txn.writeCount; later++) {
                                if (txn.writeKeys[later].equals(wKey)) {
                                    seenLater = true;
                                    break;
                                }
                            }
                            if (!seenLater && wKey.compareTo(lo) >= 0 && wKey.compareTo(hi) <= 0) {
                                boolean inSnap = PersistentTreap.get(txn.snapshotRoot, wKey) != null;
                                boolean isPut = txn.writeValOffsets[w] >= 0;
                                if (isPut && !inSnap) {
                                    actualCount++;
                                } else if (!isPut && inSnap) {
                                    actualCount--;
                                }
                            }
                        }
                    }
                    if (actualCount != expectedCount) {
                        throw new WalException(Code.RANGE_MISMATCH, recOffset);
                    }
                    // R23e
                    txn.addRange(lo, hi);
                    break;
                }
            }
        }

        // Build Result
        List<Long> incomplete = new ArrayList<>(openCount);
        if (openCount > 0) {
            long[] incArr = new long[openCount];
            for (int i = 0; i < openCount; i++) {
                incArr[i] = openTxns[i].id;
            }
            Arrays.sort(incArr);
            for (int i = 0; i < openCount; i++) {
                incomplete.add(incArr[i]);
            }
        }

        Map<String, String> finalState = new HashMap<>(Math.max(16, (int) (PersistentTreap.size(state.root) / 0.75f) + 1));
        PersistentTreap.fillMap(state.root, finalState);

        return new Result(finalState, committed, aborted, incomplete, validLength, tornTail);
    }

    private static int readU16(byte[] log, int offset) {
        return ((log[offset] & 0xFF) << 8) | (log[offset + 1] & 0xFF);
    }

    private static long readU32(byte[] log, int offset) {
        return ((long) (log[offset] & 0xFF) << 24)
             | ((long) (log[offset + 1] & 0xFF) << 16)
             | ((long) (log[offset + 2] & 0xFF) << 8)
             | ((long) (log[offset + 3] & 0xFF));
    }

    private static boolean checkFletcher16(byte[] log, int offset, int len, int expectedS2, int expectedS1) {
        int s1 = 0;
        int s2 = 0;
        int end = offset + len;
        int i = offset;
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

    private static void checkKey(byte[] log, int offset, int len, int recOffset) throws WalException {
        if (len < MIN_KEY_LEN || len > MAX_KEY_LEN) {
            throw new WalException(Code.BAD_KEY, recOffset);
        }
        int end = offset + len;
        for (int i = offset; i < end; i++) {
            int b = log[i] & 0xFF;
            if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                throw new WalException(Code.BAD_KEY, recOffset);
            }
        }
    }

    private static void checkValue(byte[] log, int offset, int len, int recOffset) throws WalException {
        int end = offset + len;
        for (int i = offset; i < end; i++) {
            int b = log[i] & 0xFF;
            if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                throw new WalException(Code.BAD_VALUE, recOffset);
            }
        }
    }

    private static String asciiString(byte[] log, int offset, int len) {
        if (len == 0) return "";
        return new String(log, offset, len, StandardCharsets.ISO_8859_1);
    }

    private static boolean bytesEqual(byte[] log, int off1, int off2, int len) {
        return Arrays.equals(log, off1, off1 + len, log, off2, off2 + len);
    }

    private static boolean stringEqualsBytes(String s, byte[] log, int off, int len) {
        if (s.length() != len) return false;
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) != (log[off + i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static final class Txn {
        final long id;
        final PersistentTreap.Node snapshotRoot;

        final String[] writeKeys = new String[MAX_WRITES];
        final int[] writeValOffsets = new int[MAX_WRITES]; // -1 for DELETE
        final int[] writeValLengths = new int[MAX_WRITES];
        int writeCount = 0;

        // Savepoints: names are in 0x21..0x7E (94 possible values)
        int[] spPos;
        int[] spRecOffset;

        HashSet<String> readSet;
        TreeMap<String, String> mergedRanges; // non-overlapping ranges: key = lo, value = hi
        String emptyRangeLo; // fast-path for 1 range before allocating TreeMap
        String emptyRangeHi;
        boolean conflicted = false;

        Txn(long id, PersistentTreap.Node snapshotRoot) {
            this.id = id;
            this.snapshotRoot = snapshotRoot;
        }

        void addPut(String key, int valOff, int valLen) {
            writeKeys[writeCount] = key;
            writeValOffsets[writeCount] = valOff;
            writeValLengths[writeCount] = valLen;
            writeCount++;
        }

        void addDelete(String key) {
            writeKeys[writeCount] = key;
            writeValOffsets[writeCount] = -1;
            writeValLengths[writeCount] = 0;
            writeCount++;
        }

        int findLastWrite(String key) {
            for (int i = writeCount - 1; i >= 0; i--) {
                if (writeKeys[i].equals(key)) {
                    return i;
                }
            }
            return -1;
        }

        void setSavepoint(int name, int recOffset) {
            int idx = name - NAME_BYTE_MIN;
            if (spPos == null) {
                spPos = new int[NAME_BYTE_MAX - NAME_BYTE_MIN + 1];
                spRecOffset = new int[NAME_BYTE_MAX - NAME_BYTE_MIN + 1];
                Arrays.fill(spPos, -1);
            }
            spPos[idx] = writeCount;
            spRecOffset[idx] = recOffset;
        }

        boolean hasSavepoint(int name) {
            if (spPos == null) return false;
            return spPos[name - NAME_BYTE_MIN] >= 0;
        }

        void rollbackTo(int name) {
            int idx = name - NAME_BYTE_MIN;
            int targetPos = spPos[idx];
            int targetOffset = spRecOffset[idx];
            for (int i = targetPos; i < writeCount; i++) {
                writeKeys[i] = null;
            }
            writeCount = targetPos;
            for (int i = 0; i < spPos.length; i++) {
                if (i != idx && spPos[i] >= 0 && spRecOffset[i] > targetOffset) {
                    spPos[i] = -1;
                    spRecOffset[i] = 0;
                }
            }
        }

        void addRead(String key) {
            if (conflicted) return;
            if (readSet == null) {
                readSet = new HashSet<>();
            }
            readSet.add(key);
        }

        void addRange(String lo, String hi) {
            if (conflicted) return;
            if (lo.compareTo(hi) > 0) {
                // Empty range can never match any written key K
                return;
            }
            if (mergedRanges == null) {
                if (emptyRangeLo == null) {
                    emptyRangeLo = lo;
                    emptyRangeHi = hi;
                    return;
                }
                mergedRanges = new TreeMap<>();
                mergedRanges.put(emptyRangeLo, emptyRangeHi);
                emptyRangeLo = null;
                emptyRangeHi = null;
            }
            String newLo = lo;
            String newHi = hi;

            Map.Entry<String, String> floor = mergedRanges.floorEntry(lo);
            if (floor != null && floor.getValue().compareTo(lo) >= 0) {
                newLo = floor.getKey();
                if (floor.getValue().compareTo(newHi) > 0) {
                    newHi = floor.getValue();
                }
                mergedRanges.remove(floor.getKey());
            }

            while (true) {
                Map.Entry<String, String> next = mergedRanges.ceilingEntry(newLo);
                if (next == null || next.getKey().compareTo(newHi) > 0) {
                    break;
                }
                if (next.getValue().compareTo(newHi) > 0) {
                    newHi = next.getValue();
                }
                mergedRanges.remove(next.getKey());
            }
            mergedRanges.put(newLo, newHi);
        }

        boolean hasReadsOrRanges() {
            return readSet != null || emptyRangeLo != null || mergedRanges != null;
        }

        boolean conflictsWith(String key) {
            if (readSet != null && readSet.contains(key)) {
                return true;
            }
            if (emptyRangeLo != null) {
                if (key.compareTo(emptyRangeLo) >= 0 && key.compareTo(emptyRangeHi) <= 0) {
                    return true;
                }
            } else if (mergedRanges != null) {
                Map.Entry<String, String> floor = mergedRanges.floorEntry(key);
                if (floor != null && key.compareTo(floor.getValue()) <= 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class PersistentTreap {
        static final class Node {
            final String key;
            final String value;
            final int priority;
            final int size;
            final Node left;
            final Node right;

            Node(String key, String value, int priority, Node left, Node right) {
                this.key = key;
                this.value = value;
                this.priority = priority;
                this.left = left;
                this.right = right;
                this.size = 1 + (left == null ? 0 : left.size) + (right == null ? 0 : right.size);
            }
        }

        Node root = null;
        private long seed = 0x9E3779B97F4A7C15L;

        private int nextPriority() {
            long z = (seed += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return (int) (z ^ (z >>> 31));
        }

        static int size(Node n) {
            return n == null ? 0 : n.size;
        }

        static String get(Node n, String key) {
            while (n != null) {
                int cmp = key.compareTo(n.key);
                if (cmp == 0) return n.value;
                n = cmp < 0 ? n.left : n.right;
            }
            return null;
        }

        void put(String key, String value) {
            root = put(root, key, value);
        }

        private Node put(Node n, String key, String value) {
            if (n == null) {
                return new Node(key, value, nextPriority(), null, null);
            }
            int cmp = key.compareTo(n.key);
            if (cmp == 0) {
                if (n.value.equals(value)) {
                    return n;
                }
                return new Node(n.key, value, n.priority, n.left, n.right);
            } else if (cmp < 0) {
                Node newLeft = put(n.left, key, value);
                if (newLeft.priority > n.priority) {
                    return new Node(newLeft.key, newLeft.value, newLeft.priority,
                            newLeft.left,
                            new Node(n.key, n.value, n.priority, newLeft.right, n.right));
                }
                return new Node(n.key, n.value, n.priority, newLeft, n.right);
            } else {
                Node newRight = put(n.right, key, value);
                if (newRight.priority > n.priority) {
                    return new Node(newRight.key, newRight.value, newRight.priority,
                            new Node(n.key, n.value, n.priority, n.left, newRight.left),
                            newRight.right);
                }
                return new Node(n.key, n.value, n.priority, n.left, newRight);
            }
        }

        void remove(String key) {
            if (get(root, key) == null) {
                return;
            }
            root = remove(root, key);
        }

        private static Node remove(Node n, String key) {
            int cmp = key.compareTo(n.key);
            if (cmp == 0) {
                return merge(n.left, n.right);
            } else if (cmp < 0) {
                return new Node(n.key, n.value, n.priority, remove(n.left, key), n.right);
            } else {
                return new Node(n.key, n.value, n.priority, n.left, remove(n.right, key));
            }
        }

        private static Node merge(Node left, Node right) {
            if (left == null) return right;
            if (right == null) return left;
            if (left.priority > right.priority) {
                return new Node(left.key, left.value, left.priority, left.left, merge(left.right, right));
            } else {
                return new Node(right.key, right.value, right.priority, merge(left, right.left), right.right);
            }
        }

        static int countRange(Node root, String lo, String hi) {
            if (root == null) return 0;
            return countLessOrEqual(root, hi) - countLess(root, lo);
        }

        private static int countLess(Node n, String key) {
            int count = 0;
            while (n != null) {
                if (n.key.compareTo(key) < 0) {
                    count += 1 + size(n.left);
                    n = n.right;
                } else {
                    n = n.left;
                }
            }
            return count;
        }

        private static int countLessOrEqual(Node n, String key) {
            int count = 0;
            while (n != null) {
                if (n.key.compareTo(key) <= 0) {
                    count += 1 + size(n.left);
                    n = n.right;
                } else {
                    n = n.left;
                }
            }
            return count;
        }

        static void fillMap(Node root, Map<String, String> map) {
            if (root == null) return;
            List<Node> stack = new ArrayList<>(64);
            Node curr = root;
            while (curr != null || !stack.isEmpty()) {
                while (curr != null) {
                    stack.add(curr);
                    curr = curr.left;
                }
                curr = stack.remove(stack.size() - 1);
                map.put(curr.key, curr.value);
                curr = curr.right;
            }
        }
    }

    private static final class IntList {
        private int[] data;
        private int size;

        IntList(int initialCapacity) {
            data = new int[Math.max(16, initialCapacity)];
        }

        void add(int val) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = val;
        }

        int get(int idx) {
            return data[idx];
        }

        int size() {
            return size;
        }
    }

    private static final class LongHashSet {
        private long[] keys;
        private int mask;
        private int size;
        private int threshold;

        LongHashSet(int initialCapacity) {
            int cap = 16;
            while (cap < initialCapacity) cap <<= 1;
            keys = new long[cap];
            mask = cap - 1;
            threshold = (int) (cap * 0.7f);
        }

        private static int hash(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32));
        }

        boolean contains(long key) {
            // Valid txnIds are always >= 1, so 0 represents an empty slot
            int idx = hash(key) & mask;
            while (true) {
                long k = keys[idx];
                if (k == 0) return false;
                if (k == key) return true;
                idx = (idx + 1) & mask;
            }
        }

        void add(long key) {
            if (size >= threshold) {
                rehash();
            }
            int idx = hash(key) & mask;
            while (true) {
                long k = keys[idx];
                if (k == 0) {
                    keys[idx] = key;
                    size++;
                    return;
                }
                if (k == key) return;
                idx = (idx + 1) & mask;
            }
        }

        private void rehash() {
            long[] oldKeys = keys;
            int newCap = oldKeys.length << 1;
            long[] newKeys = new long[newCap];
            int newMask = newCap - 1;
            for (long k : oldKeys) {
                if (k != 0) {
                    int idx = hash(k) & newMask;
                    while (newKeys[idx] != 0) {
                        idx = (idx + 1) & newMask;
                    }
                    newKeys[idx] = k;
                }
            }
            keys = newKeys;
            mask = newMask;
            threshold = (int) (newCap * 0.7f);
        }
    }
}
