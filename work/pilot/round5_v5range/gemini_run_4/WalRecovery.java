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

    public static Result recover(byte[] log) throws WalException {
        Objects.requireNonNull(log);
        final int L = log.length;

        // R2
        if (L == 0) {
            return new Result(Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, false);
        }

        final byte[] MAGIC = {0x57, 0x41, 0x4C, 0x31};

        // R3
        if (L < 4) {
            for (int i = 0; i < L; i++) {
                if (log[i] != MAGIC[i]) {
                    throw new WalException(Code.BAD_MAGIC, 0);
                }
            }
            return new Result(Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, true);
        }

        // R4
        if (log[0] != MAGIC[0] || log[1] != MAGIC[1] || log[2] != MAGIC[2] || log[3] != MAGIC[3]) {
            throw new WalException(Code.BAD_MAGIC, 0);
        }

        // Phase 1: frames
        int p = 4;
        int validLength = 4;
        boolean tornTail = false;

        while (true) {
            // R5
            if (p == L) {
                validLength = L;
                tornTail = false;
                break;
            }
            // R6
            int rem = L - p;
            if (rem == 1 || rem == 2) {
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
            if (N > 1024) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }
            // R9
            int nextP = p + 5 + N;
            if (nextP > L) {
                validLength = p;
                tornTail = true;
                break;
            }
            // R10: Checksum Fletcher-16
            int s1 = 0;
            int s2 = 0;
            int endPayload = p + 3 + N;
            int idx = p;
            while (idx < endPayload) {
                int chunkEnd = Math.min(idx + 5802, endPayload);
                while (idx < chunkEnd) {
                    s1 += log[idx++] & 0xFF;
                    s2 += s1;
                }
                s1 %= 255;
                s2 %= 255;
            }
            int storedS2 = log[endPayload] & 0xFF;
            int storedS1 = log[endPayload + 1] & 0xFF;
            if (storedS2 != s2 || storedS1 != s1) {
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
        Txn[] openTxns = new Txn[4];
        int openCount = 0;

        LongArrayList committed = new LongArrayList();
        LongArrayList aborted = new LongArrayList();

        CommitLog commits = new CommitLog();
        int[] sortBuf = new int[4];

        p = 4;
        while (p < validLength) {
            int type = log[p] & 0xFF;
            int N = ((log[p + 1] & 0xFF) << 8) | (log[p + 2] & 0xFF);
            int pay = p + 3;

            // R13: Payload layout
            switch (type) {
                case 1, 4, 5 -> {
                    if (N != 4) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                case 2 -> {
                    if (N < 5) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int k = log[pay + 4] & 0xFF;
                    if (N < 7 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int v = u16(log, pay + 5 + k);
                    if (N != 7 + k + v) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                case 3 -> {
                    if (N < 5) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int k = log[pay + 4] & 0xFF;
                    if (N != 5 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                case 6, 7 -> {
                    if (N != 5) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                case 8 -> {
                    if (N < 1) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int c = log[pay] & 0xFF;
                    if (N != 1 + 4 * c) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                case 9 -> {
                    if (N < 6) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int k = log[pay + 4] & 0xFF;
                    if (N < 6 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int f = log[pay + 5 + k] & 0xFF;
                    if (f == 0) {
                        if (N != 6 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    } else if (f == 1) {
                        if (N < 8 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                        int v = u16(log, pay + 6 + k);
                        if (N != 8 + k + v) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    } else {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                }
                case 10 -> {
                    if (N < 6) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int k1 = log[pay + 4] & 0xFF;
                    if (N < 6 + k1) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    int k2 = log[pay + 5 + k1] & 0xFF;
                    if (N != 10 + k1 + k2) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
            }

            // R14: Txn ids
            int txnId = 0;
            if (type != 8) {
                txnId = i32(log, pay);
                if (txnId == 0) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
            } else {
                int c = log[pay] & 0xFF;
                for (int i = 0; i < c; i++) {
                    if (i32(log, pay + 1 + 4 * i) == 0) {
                        throw new WalException(Code.BAD_TXN_ID, p);
                    }
                }
            }

            // R15: Key
            if (type == 2 || type == 3 || type == 9) {
                int k = log[pay + 4] & 0xFF;
                checkKey(log, pay + 5, k, p);
            } else if (type == 10) {
                int k1 = log[pay + 4] & 0xFF;
                checkKey(log, pay + 5, k1, p);
                int k2 = log[pay + 5 + k1] & 0xFF;
                checkKey(log, pay + 6 + k1, k2, p);
            }

            // R16: Value
            if (type == 2) {
                int k = log[pay + 4] & 0xFF;
                int v = u16(log, pay + 5 + k);
                checkValue(log, pay + 7 + k, v, p);
            } else if (type == 9) {
                int k = log[pay + 4] & 0xFF;
                int f = log[pay + 5 + k] & 0xFF;
                if (f == 1) {
                    int v = u16(log, pay + 6 + k);
                    checkValue(log, pay + 8 + k, v, p);
                }
            }

            // R16a: Name
            if (type == 6 || type == 7) {
                int name = log[pay + 4] & 0xFF;
                if (name < 0x21 || name > 0x7E) {
                    throw new WalException(Code.BAD_NAME, p);
                }
            }

            // R17 & R18 (BEGIN)
            if (type == 1) {
                if (!seenTxns.add(txnId)) {
                    throw new WalException(Code.DUPLICATE_TXN, p);
                }
                if (openCount == 4) {
                    throw new WalException(Code.TOO_MANY_OPEN, p);
                }
                // R20: BEGIN action
                openTxns[openCount++] = new Txn(txnId, p, currentState.root);
                p += 5 + N;
                continue;
            }

            // R19c & R23c (CHECKPOINT)
            if (type == 8) {
                int c = log[pay] & 0xFF;
                if (c != openCount) {
                    throw new WalException(Code.CHECKPOINT_MISMATCH, p);
                }
                for (int i = 0; i < openCount; i++) {
                    sortBuf[i] = openTxns[i].id;
                }
                // Insertion sort unsigned
                for (int i = 1; i < openCount; i++) {
                    int key = sortBuf[i];
                    int j = i - 1;
                    while (j >= 0 && Integer.compareUnsigned(sortBuf[j], key) > 0) {
                        sortBuf[j + 1] = sortBuf[j];
                        j--;
                    }
                    sortBuf[j + 1] = key;
                }
                for (int i = 0; i < c; i++) {
                    int listed = i32(log, pay + 1 + 4 * i);
                    if (listed != sortBuf[i]) {
                        throw new WalException(Code.CHECKPOINT_MISMATCH, p);
                    }
                }
                p += 5 + N;
                continue;
            }

            // R19: Open check for types 2, 3, 4, 5, 6, 7, 9, 10
            int openIdx = -1;
            for (int i = 0; i < openCount; i++) {
                if (openTxns[i].id == txnId) {
                    openIdx = i;
                    break;
                }
            }
            if (openIdx < 0) {
                throw new WalException(Code.TXN_NOT_OPEN, p);
            }
            Txn T = openTxns[openIdx];

            switch (type) {
                case 2 -> { // PUT
                    // R19a
                    if (T.writeCount == 8) {
                        throw new WalException(Code.TXN_TOO_LARGE, p);
                    }
                    int k = log[pay + 4] & 0xFF;
                    String keyStr = asciiString(log, pay + 5, k);
                    int v = u16(log, pay + 5 + k);
                    String valStr = asciiString(log, pay + 7 + k, v);
                    // R21
                    T.addWrite(keyStr, valStr);
                }
                case 3 -> { // DELETE
                    // R19a
                    if (T.writeCount == 8) {
                        throw new WalException(Code.TXN_TOO_LARGE, p);
                    }
                    int k = log[pay + 4] & 0xFF;
                    String keyStr = asciiString(log, pay + 5, k);
                    // R21
                    T.addWrite(keyStr, null);
                }
                case 4 -> { // COMMIT (R22)
                    boolean conflict = false;
                    if (T.hasReadsOrRanges()) {
                        conflict = commits.hasConflict(T);
                    }
                    if (!conflict) {
                        for (int i = 0; i < T.writeCount; i++) {
                            String k = T.writeKeys[i];
                            String v = T.writeValues[i];
                            if (v != null) {
                                currentState.put(k, v);
                            } else {
                                currentState.remove(k);
                            }
                        }
                        if (T.writeCount > 0) {
                            commits.recordCommit(p, T.writeKeys, T.writeCount);
                        }
                        committed.add(Integer.toUnsignedLong(txnId));
                    } else {
                        aborted.add(Integer.toUnsignedLong(txnId));
                    }
                    // Close transaction
                    openTxns[openIdx] = openTxns[--openCount];
                    openTxns[openCount] = null;
                }
                case 5 -> { // ABORT (R23)
                    aborted.add(Integer.toUnsignedLong(txnId));
                    openTxns[openIdx] = openTxns[--openCount];
                    openTxns[openCount] = null;
                }
                case 6 -> { // SAVEPOINT (R23a)
                    int name = log[pay + 4] & 0xFF;
                    T.setSavepoint(name, T.writeCount, p);
                }
                case 7 -> { // ROLLBACK (R19b, R23b)
                    int name = log[pay + 4] & 0xFF;
                    if (!T.rollback(name)) {
                        throw new WalException(Code.NO_SAVEPOINT, p);
                    }
                }
                case 9 -> { // READ (R19d, R23d)
                    int k = log[pay + 4] & 0xFF;
                    String keyStr = asciiString(log, pay + 5, k);
                    int f = log[pay + 5 + k] & 0xFF;
                    String viewVal = T.getView(keyStr);
                    if (f == 0) {
                        if (viewVal != null) {
                            throw new WalException(Code.READ_MISMATCH, p);
                        }
                    } else {
                        int v = u16(log, pay + 6 + k);
                        if (viewVal == null || !equalsAscii(viewVal, log, pay + 8 + k, v)) {
                            throw new WalException(Code.READ_MISMATCH, p);
                        }
                    }
                    T.addRead(keyStr);
                }
                case 10 -> { // RANGE (R19e, R23e)
                    int k1 = log[pay + 4] & 0xFF;
                    String lo = asciiString(log, pay + 5, k1);
                    int k2 = log[pay + 5 + k1] & 0xFF;
                    String hi = asciiString(log, pay + 6 + k1, k2);
                    long expectedCount = Integer.toUnsignedLong(i32(log, pay + 6 + k1 + k2));
                    int actualCount = T.countViewRange(lo, hi);
                    if ((long) actualCount != expectedCount) {
                        throw new WalException(Code.RANGE_MISMATCH, p);
                    }
                    T.addRange(lo, hi);
                }
            }

            p += 5 + N;
        }

        // Build Result (R24 - R27)
        Map<String, String> finalState = new HashMap<>(Math.max(16, (int) (currentState.size() / 0.75f) + 1));
        currentState.populateMap(finalState);

        List<Long> incomplete = new ArrayList<>(openCount);
        for (int i = 0; i < openCount; i++) {
            sortBuf[i] = openTxns[i].id;
        }
        for (int i = 1; i < openCount; i++) {
            int key = sortBuf[i];
            int j = i - 1;
            while (j >= 0 && Integer.compareUnsigned(sortBuf[j], key) > 0) {
                sortBuf[j + 1] = sortBuf[j];
                j--;
            }
            sortBuf[j + 1] = key;
        }
        for (int i = 0; i < openCount; i++) {
            incomplete.add(Integer.toUnsignedLong(sortBuf[i]));
        }

        return new Result(
                finalState,
                committed.toList(),
                aborted.toList(),
                incomplete,
                validLength,
                tornTail
        );
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int i32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static void checkKey(byte[] log, int off, int len, int p) throws WalException {
        if (len < 1 || len > 32) {
            throw new WalException(Code.BAD_KEY, p);
        }
        int end = off + len;
        for (int i = off; i < end; i++) {
            int c = log[i] & 0xFF;
            if (c < 0x21 || c > 0x7E) {
                throw new WalException(Code.BAD_KEY, p);
            }
        }
    }

    private static void checkValue(byte[] log, int off, int len, int p) throws WalException {
        int end = off + len;
        for (int i = off; i < end; i++) {
            int c = log[i] & 0xFF;
            if (c < 0x20 || c > 0x7E) {
                throw new WalException(Code.BAD_VALUE, p);
            }
        }
    }

    private static String asciiString(byte[] log, int off, int len) {
        if (len == 0) return "";
        return new String(log, off, len, StandardCharsets.ISO_8859_1);
    }

    private static boolean equalsAscii(String s, byte[] log, int off, int len) {
        if (s.length() != len) return false;
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) != (log[off + i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static final class Txn {
        final int id;
        final int beginOffset;
        final TreapNode snapshot;

        final String[] writeKeys = new String[8];
        final String[] writeValues = new String[8]; // null means DELETE
        int writeCount = 0;

        byte[] spNames;
        byte[] spPositions;
        int[] spOffsets;
        int spCount = 0;

        HashSet<String> readSet;
        ArrayList<String> rangeLos;
        ArrayList<String> rangeHis;

        Txn(int id, int beginOffset, TreapNode snapshot) {
            this.id = id;
            this.beginOffset = beginOffset;
            this.snapshot = snapshot;
        }

        void addWrite(String key, String value) {
            writeKeys[writeCount] = key;
            writeValues[writeCount] = value;
            writeCount++;
        }

        void setSavepoint(int name, int pos, int recordOffset) {
            if (spNames == null) {
                spNames = new byte[8];
                spPositions = new byte[8];
                spOffsets = new int[8];
            }
            for (int i = 0; i < spCount; i++) {
                if ((spNames[i] & 0xFF) == name) {
                    spPositions[i] = (byte) pos;
                    spOffsets[i] = recordOffset;
                    return;
                }
            }
            if (spCount == spNames.length) {
                int newCap = spNames.length * 2;
                spNames = Arrays.copyOf(spNames, newCap);
                spPositions = Arrays.copyOf(spPositions, newCap);
                spOffsets = Arrays.copyOf(spOffsets, newCap);
            }
            spNames[spCount] = (byte) name;
            spPositions[spCount] = (byte) pos;
            spOffsets[spCount] = recordOffset;
            spCount++;
        }

        boolean rollback(int name) {
            if (spCount == 0) return false;
            int foundIdx = -1;
            for (int i = 0; i < spCount; i++) {
                if ((spNames[i] & 0xFF) == name) {
                    foundIdx = i;
                    break;
                }
            }
            if (foundIdx < 0) return false;

            int targetPos = spPositions[foundIdx] & 0xFF;
            int targetOffset = spOffsets[foundIdx];

            for (int i = targetPos; i < writeCount; i++) {
                writeKeys[i] = null;
                writeValues[i] = null;
            }
            writeCount = targetPos;

            int newSpCount = 0;
            for (int i = 0; i < spCount; i++) {
                if (spOffsets[i] <= targetOffset) {
                    spNames[newSpCount] = spNames[i];
                    spPositions[newSpCount] = spPositions[i];
                    spOffsets[newSpCount] = spOffsets[i];
                    newSpCount++;
                }
            }
            spCount = newSpCount;
            return true;
        }

        String getView(String key) {
            for (int i = writeCount - 1; i >= 0; i--) {
                if (writeKeys[i].equals(key)) {
                    return writeValues[i];
                }
            }
            return PersistentTreap.get(snapshot, key);
        }

        int countViewRange(String lo, String hi) {
            if (lo.compareTo(hi) > 0) {
                return 0;
            }
            int count = PersistentTreap.countRange(snapshot, lo, hi);
            if (writeCount == 0) {
                return count;
            }
            for (int i = 0; i < writeCount; i++) {
                String k = writeKeys[i];
                if (k.compareTo(lo) < 0 || k.compareTo(hi) > 0) {
                    continue;
                }
                // Process each distinct key in writeList only at its last occurrence
                boolean seenLater = false;
                for (int j = i + 1; j < writeCount; j++) {
                    if (writeKeys[j].equals(k)) {
                        seenLater = true;
                        break;
                    }
                }
                if (seenLater) continue;

                boolean inSnapshot = PersistentTreap.get(snapshot, k) != null;
                boolean inWrite = writeValues[i] != null;
                if (inWrite && !inSnapshot) {
                    count++;
                } else if (!inWrite && inSnapshot) {
                    count--;
                }
            }
            return count;
        }

        void addRead(String key) {
            if (readSet == null) {
                readSet = new HashSet<>(8);
            }
            readSet.add(key);
        }

        void addRange(String lo, String hi) {
            if (rangeLos == null) {
                rangeLos = new ArrayList<>(4);
                rangeHis = new ArrayList<>(4);
            }
            rangeLos.add(lo);
            rangeHis.add(hi);
        }

        boolean hasReadsOrRanges() {
            return (readSet != null && !readSet.isEmpty()) || (rangeLos != null && !rangeLos.isEmpty());
        }
    }

    private static final class CommitLog {
        int[] commitOffsets = new int[256];
        int[] keyStarts = new int[256];
        int commitCount = 0;

        String[] keys = new String[1024];
        int keyCount = 0;

        void recordCommit(int commitOffset, String[] writeKeys, int writeCount) {
            if (commitCount == commitOffsets.length) {
                int newCap = commitOffsets.length * 2;
                commitOffsets = Arrays.copyOf(commitOffsets, newCap);
                keyStarts = Arrays.copyOf(keyStarts, newCap + 1);
            } else if (commitCount + 1 >= keyStarts.length) {
                keyStarts = Arrays.copyOf(keyStarts, keyStarts.length * 2);
            }
            commitOffsets[commitCount] = commitOffset;
            keyStarts[commitCount] = keyCount;

            for (int i = 0; i < writeCount; i++) {
                String k = writeKeys[i];
                boolean dup = false;
                for (int j = 0; j < i; j++) {
                    if (writeKeys[j].equals(k)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    if (keyCount == keys.length) {
                        keys = Arrays.copyOf(keys, keys.length * 2);
                    }
                    keys[keyCount++] = k;
                }
            }
            commitCount++;
            keyStarts[commitCount] = keyCount;
        }

        boolean hasConflict(Txn T) {
            if (commitCount == 0) return false;
            // Binary search for first commitOffset > T.beginOffset
            int lo = 0;
            int hi = commitCount;
            int target = T.beginOffset;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (commitOffsets[mid] <= target) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            if (lo >= commitCount) return false;

            int startKeyIdx = keyStarts[lo];
            int endKeyIdx = keyStarts[commitCount];

            HashSet<String> reads = T.readSet;
            ArrayList<String> rLos = T.rangeLos;
            ArrayList<String> rHis = T.rangeHis;
            int numRanges = rLos == null ? 0 : rLos.size();

            for (int idx = startKeyIdx; idx < endKeyIdx; idx++) {
                String writtenKey = keys[idx];
                if (reads != null && reads.contains(writtenKey)) {
                    return true;
                }
                for (int r = 0; r < numRanges; r++) {
                    String rLo = rLos.get(r);
                    String rHi = rHis.get(r);
                    if (rLo.compareTo(writtenKey) <= 0 && writtenKey.compareTo(rHi) <= 0) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static final class TreapNode {
        final String key;
        final String value;
        final int priority;
        final int size;
        final TreapNode left;
        final TreapNode right;

        TreapNode(String key, String value, int priority, TreapNode left, TreapNode right) {
            this.key = key;
            this.value = value;
            this.priority = priority;
            this.left = left;
            this.right = right;
            this.size = 1 + (left == null ? 0 : left.size) + (right == null ? 0 : right.size);
        }
    }

    private static final class PersistentTreap {
        TreapNode root;
        private long prngState = 0x9E3779B97F4A7C15L;

        private int nextPriority() {
            long z = (prngState += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return (int) (z ^ (z >>> 31));
        }

        int size() {
            return root == null ? 0 : root.size;
        }

        void put(String key, String value) {
            root = insert(root, key, value);
        }

        private TreapNode insert(TreapNode node, String key, String value) {
            if (node == null) {
                return new TreapNode(key, value, nextPriority(), null, null);
            }
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                if (value.equals(node.value)) return node;
                return new TreapNode(key, value, node.priority, node.left, node.right);
            } else if (cmp < 0) {
                TreapNode newLeft = insert(node.left, key, value);
                if (newLeft.priority > node.priority) {
                    // Rotate right
                    TreapNode newRight = new TreapNode(node.key, node.value, node.priority, newLeft.right, node.right);
                    return new TreapNode(newLeft.key, newLeft.value, newLeft.priority, newLeft.left, newRight);
                }
                return new TreapNode(node.key, node.value, node.priority, newLeft, node.right);
            } else {
                TreapNode newRight = insert(node.right, key, value);
                if (newRight.priority > node.priority) {
                    // Rotate left
                    TreapNode newLeft = new TreapNode(node.key, node.value, node.priority, node.left, newRight.left);
                    return new TreapNode(newRight.key, newRight.value, newRight.priority, newLeft, newRight.right);
                }
                return new TreapNode(node.key, node.value, node.priority, node.left, newRight);
            }
        }

        void remove(String key) {
            if (get(root, key) == null) return;
            root = delete(root, key);
        }

        private static TreapNode delete(TreapNode node, String key) {
            if (node == null) return null;
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                return merge(node.left, node.right);
            } else if (cmp < 0) {
                TreapNode newLeft = delete(node.left, key);
                return new TreapNode(node.key, node.value, node.priority, newLeft, node.right);
            } else {
                TreapNode newRight = delete(node.right, key);
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
                node = (cmp < 0) ? node.left : node.right;
            }
            return null;
        }

        static int countLess(TreapNode node, String key) {
            int count = 0;
            while (node != null) {
                int cmp = node.key.compareTo(key);
                if (cmp < 0) {
                    count += 1 + (node.left == null ? 0 : node.left.size);
                    node = node.right;
                } else {
                    node = node.left;
                }
            }
            return count;
        }

        static int countLessOrEqual(TreapNode node, String key) {
            int count = 0;
            while (node != null) {
                int cmp = node.key.compareTo(key);
                if (cmp <= 0) {
                    count += 1 + (node.left == null ? 0 : node.left.size);
                    node = node.right;
                } else {
                    node = node.left;
                }
            }
            return count;
        }

        static int countRange(TreapNode node, String lo, String hi) {
            if (node == null) return 0;
            return countLessOrEqual(node, hi) - countLess(node, lo);
        }

        void populateMap(Map<String, String> map) {
            populate(root, map);
        }

        private static void populate(TreapNode node, Map<String, String> map) {
            if (node == null) return;
            populate(node.left, map);
            map.put(node.key, node.value);
            populate(node.right, map);
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
            threshold = (int) (cap * 0.7);
        }

        boolean add(int key) {
            // key is never 0 (R14 guarantees txn != 0)
            int idx = mix(key) & mask;
            while (true) {
                int cur = table[idx];
                if (cur == 0) {
                    table[idx] = key;
                    if (++size >= threshold) {
                        rehash();
                    }
                    return true;
                }
                if (cur == key) {
                    return false;
                }
                idx = (idx + 1) & mask;
            }
        }

        private void rehash() {
            int[] oldTable = table;
            int newCap = oldTable.length << 1;
            int[] newTable = new int[newCap];
            int newMask = newCap - 1;
            for (int key : oldTable) {
                if (key != 0) {
                    int idx = mix(key) & newMask;
                    while (newTable[idx] != 0) {
                        idx = (idx + 1) & newMask;
                    }
                    newTable[idx] = key;
                }
            }
            table = newTable;
            mask = newMask;
            threshold = (int) (newCap * 0.7);
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

    private static final class LongArrayList {
        private long[] data = new long[64];
        private int size = 0;

        void add(long val) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = val;
        }

        List<Long> toList() {
            if (size == 0) return Collections.emptyList();
            Long[] arr = new Long[size];
            for (int i = 0; i < size; i++) {
                arr[i] = data[i];
            }
            return Arrays.asList(arr);
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
}
