import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WalRecovery {
    private WalRecovery() {}

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

    private static final byte[] MAGIC = {'W', 'A', 'L', '1'};

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        // R1
        if (log == null) {
            throw new NullPointerException("log is null");
        }
        int L = log.length;
        // R2
        if (L == 0) {
            return emptyResult(0, false);
        }
        // R3
        if (L < HEADER_SIZE) {
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
        int validLength = L;
        boolean tornTail = false;
        int p = HEADER_SIZE;

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
            int N = readU16(log, p + 1);
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
            if (!checkChecksum(log, p, 3 + N, log[p + 3 + N] & 0xFF, log[p + 4 + N] & 0xFF)) {
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
        PersistentTreap state = new PersistentTreap();
        IntHashSet seenTxns = new IntHashSet(1024);
        Txn[] openTxns = new Txn[MAX_OPEN_TXNS];
        int openCount = 0;

        CommittedLog committedLog = new CommittedLog();
        LongArray committedList = new LongArray();
        LongArray abortedList = new LongArray();

        long[] tempCheckpointIds = new long[MAX_OPEN_TXNS];

        p = HEADER_SIZE;
        while (p < validLength) {
            int type = log[p] & 0xFF;
            int N = readU16(log, p + 1);
            int pay = p + 3;

            // R13: Payload layout
            checkLayout(log, p, type, N, pay);

            // R14: Txn ids
            long txnId = 0;
            if (type == 8) {
                int c = log[pay] & 0xFF;
                for (int i = 0; i < c; i++) {
                    long listedId = readU32(log, pay + 1 + 4 * i);
                    if (listedId == 0) {
                        throw new WalException(Code.BAD_TXN_ID, p);
                    }
                }
            } else {
                txnId = readU32(log, pay);
                if (txnId == 0) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
            }

            // R15: Key
            if (type == 2 || type == 3 || type == 9) {
                int k = log[pay + 4] & 0xFF;
                checkKeyBytes(log, pay + 5, k, p);
            } else if (type == 10) {
                int k1 = log[pay + 4] & 0xFF;
                checkKeyBytes(log, pay + 5, k1, p);
                int k2 = log[pay + 5 + k1] & 0xFF;
                checkKeyBytes(log, pay + 6 + k1, k2, p);
            }

            // R16: Value
            if (type == 2) {
                int k = log[pay + 4] & 0xFF;
                int v = readU16(log, pay + 5 + k);
                checkValueBytes(log, pay + 7 + k, v, p);
            } else if (type == 9) {
                int k = log[pay + 4] & 0xFF;
                int f = log[pay + 5 + k] & 0xFF;
                if (f == 1) {
                    int v = readU16(log, pay + 6 + k);
                    checkValueBytes(log, pay + 8 + k, v, p);
                }
            }

            // R16a: Name
            if (type == 6 || type == 7) {
                int name = log[pay + 4] & 0xFF;
                if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) {
                    throw new WalException(Code.BAD_NAME, p);
                }
            }

            // Transaction checks & Actions
            switch (type) {
                case 1 -> { // BEGIN
                    // R17: Reused id
                    int rawTxnId = (int) txnId;
                    if (seenTxns.contains(rawTxnId)) {
                        throw new WalException(Code.DUPLICATE_TXN, p);
                    }
                    // R18: Open limit
                    if (openCount == MAX_OPEN_TXNS) {
                        throw new WalException(Code.TOO_MANY_OPEN, p);
                    }
                    // R20: BEGIN action
                    seenTxns.add(rawTxnId);
                    Txn txn = openTxns[openCount];
                    if (txn == null) {
                        txn = new Txn();
                        openTxns[openCount] = txn;
                    }
                    txn.init(txnId, state.root, committedLog.commitCount);
                    openCount++;
                }
                case 2 -> { // PUT
                    // R19: Open check
                    int idx = findOpenTxn(openTxns, openCount, txnId, p);
                    Txn txn = openTxns[idx];
                    // R19a: Write limit
                    if (txn.writeCount == MAX_WRITES) {
                        throw new WalException(Code.TXN_TOO_LARGE, p);
                    }
                    // R21: PUT action
                    int k = log[pay + 4] & 0xFF;
                    String key = asciiString(log, pay + 5, k);
                    int v = readU16(log, pay + 5 + k);
                    String val = asciiString(log, pay + 7 + k, v);
                    txn.addWrite(key, val);
                }
                case 3 -> { // DELETE
                    // R19: Open check
                    int idx = findOpenTxn(openTxns, openCount, txnId, p);
                    Txn txn = openTxns[idx];
                    // R19a: Write limit
                    if (txn.writeCount == MAX_WRITES) {
                        throw new WalException(Code.TXN_TOO_LARGE, p);
                    }
                    // R21: DELETE action
                    int k = log[pay + 4] & 0xFF;
                    String key = asciiString(log, pay + 5, k);
                    txn.addWrite(key, null);
                }
                case 4 -> { // COMMIT
                    // R19: Open check
                    int idx = findOpenTxn(openTxns, openCount, txnId, p);
                    Txn txn = openTxns[idx];
                    // R22: COMMIT action
                    boolean conflict = txn.hasConflict(committedLog);
                    if (!conflict) {
                        // Apply writes & record in committedLog
                        if (txn.writeCount > 0) {
                            committedLog.beginCommit();
                            for (int i = 0; i < txn.writeCount; i++) {
                                String k = txn.writeKeys[i];
                                String v = txn.writeVals[i];
                                if (v != null) {
                                    state.put(k, v);
                                } else {
                                    state.remove(k);
                                }
                                // Deduplicate keys within this transaction's commit record if desired
                                boolean dup = false;
                                for (int j = 0; j < i; j++) {
                                    if (txn.writeKeys[j].equals(k)) {
                                        dup = true;
                                        break;
                                    }
                                }
                                if (!dup) {
                                    committedLog.addKey(k);
                                }
                            }
                            committedLog.endCommit();
                        } else {
                            committedLog.recordEmptyCommit();
                        }
                        committedList.add(txnId);
                    } else {
                        abortedList.add(txnId);
                    }
                    txn.clear();
                    closeOpenTxn(openTxns, idx, openCount);
                    openCount--;
                }
                case 5 -> { // ABORT
                    // R19: Open check
                    int idx = findOpenTxn(openTxns, openCount, txnId, p);
                    Txn txn = openTxns[idx];
                    // R23: ABORT action
                    abortedList.add(txnId);
                    txn.clear();
                    closeOpenTxn(openTxns, idx, openCount);
                    openCount--;
                }
                case 6 -> { // SAVEPOINT
                    // R19: Open check
                    int idx = findOpenTxn(openTxns, openCount, txnId, p);
                    Txn txn = openTxns[idx];
                    // R23a: SAVEPOINT action
                    int name = log[pay + 4] & 0xFF;
                    txn.setSavepoint(name, p);
                }
                case 7 -> { // ROLLBACK
                    // R19: Open check
                    int idx = findOpenTxn(openTxns, openCount, txnId, p);
                    Txn txn = openTxns[idx];
                    // R19b: Savepoint check
                    int name = log[pay + 4] & 0xFF;
                    if (!txn.hasSavepoint(name)) {
                        throw new WalException(Code.NO_SAVEPOINT, p);
                    }
                    // R23b: ROLLBACK action
                    txn.rollbackTo(name);
                }
                case 8 -> { // CHECKPOINT
                    // R19c: Checkpoint check
                    int c = log[pay] & 0xFF;
                    if (c != openCount) {
                        throw new WalException(Code.CHECKPOINT_MISMATCH, p);
                    }
                    for (int i = 0; i < openCount; i++) {
                        tempCheckpointIds[i] = openTxns[i].id;
                    }
                    Arrays.sort(tempCheckpointIds, 0, openCount);
                    for (int i = 0; i < c; i++) {
                        long listed = readU32(log, pay + 1 + 4 * i);
                        if (listed != tempCheckpointIds[i]) {
                            throw new WalException(Code.CHECKPOINT_MISMATCH, p);
                        }
                    }
                    // R23c: CHECKPOINT action (no-op)
                }
                case 9 -> { // READ
                    // R19: Open check
                    int idx = findOpenTxn(openTxns, openCount, txnId, p);
                    Txn txn = openTxns[idx];
                    // R19d: Read check
                    int k = log[pay + 4] & 0xFF;
                    String key = asciiString(log, pay + 5, k);
                    int f = log[pay + 5 + k] & 0xFF;
                    String viewVal = txn.getView(key);
                    if (f == 0) {
                        if (viewVal != null) {
                            throw new WalException(Code.READ_MISMATCH, p);
                        }
                    } else {
                        if (viewVal == null) {
                            throw new WalException(Code.READ_MISMATCH, p);
                        }
                        int v = readU16(log, pay + 6 + k);
                        if (!equalsAscii(viewVal, log, pay + 8 + k, v)) {
                            throw new WalException(Code.READ_MISMATCH, p);
                        }
                    }
                    // R23d: READ action
                    txn.addReadKey(key);
                }
                case 10 -> { // RANGE
                    // R19: Open check
                    int idx = findOpenTxn(openTxns, openCount, txnId, p);
                    Txn txn = openTxns[idx];
                    // R19e: Range check
                    int k1 = log[pay + 4] & 0xFF;
                    String lo = asciiString(log, pay + 5, k1);
                    int k2 = log[pay + 5 + k1] & 0xFF;
                    String hi = asciiString(log, pay + 6 + k1, k2);
                    long expectedCount = readU32(log, pay + 6 + k1 + k2);

                    int actualCount = txn.countRangeView(lo, hi);
                    if ((long) actualCount != expectedCount) {
                        throw new WalException(Code.RANGE_MISMATCH, p);
                    }
                    // R23e: RANGE action
                    txn.addReadRange(lo, hi);
                }
            }

            p += 5 + N;
        }

        // Build Result (R24 - R27)
        Map<String, String> finalState = new HashMap<>();
        state.collectToMap(finalState);

        List<Long> committed = committedList.toList();
        List<Long> aborted = abortedList.toList();

        List<Long> incomplete;
        if (openCount == 0) {
            incomplete = Collections.emptyList();
        } else {
            long[] incArr = new long[openCount];
            for (int i = 0; i < openCount; i++) {
                incArr[i] = openTxns[i].id;
            }
            Arrays.sort(incArr);
            incomplete = new ArrayList<>(openCount);
            for (long id : incArr) {
                incomplete.add(id);
            }
        }

        return new Result(finalState, committed, aborted, incomplete, validLength, tornTail);
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

    private static int readU16(byte[] log, int offset) {
        return ((log[offset] & 0xFF) << 8) | (log[offset + 1] & 0xFF);
    }

    private static long readU32(byte[] log, int offset) {
        return ((long) (log[offset] & 0xFF) << 24)
                | ((long) (log[offset + 1] & 0xFF) << 16)
                | ((long) (log[offset + 2] & 0xFF) << 8)
                | ((long) (log[offset + 3] & 0xFF));
    }

    private static boolean checkChecksum(byte[] log, int start, int len, int expectedS2, int expectedS1) {
        int s1 = 0;
        int s2 = 0;
        int end = start + len;
        int i = start;
        while (i < end) {
            int chunkEnd = Math.min(end, i + 4096);
            while (i < chunkEnd) {
                s1 += (log[i++] & 0xFF);
                s2 += s1;
            }
            s1 %= 255;
            s2 %= 255;
        }
        return s2 == expectedS2 && s1 == expectedS1;
    }

    private static void checkLayout(byte[] log, int p, int type, int N, int pay) throws WalException {
        switch (type) {
            case 1, 4, 5 -> {
                if (N != 4) throw new WalException(Code.MALFORMED_PAYLOAD, p);
            }
            case 2 -> {
                if (N < 5) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                int k = log[pay + 4] & 0xFF;
                if (N < 7 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                int v = readU16(log, pay + 5 + k);
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
                    int v = readU16(log, pay + 6 + k);
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
    }

    private static void checkKeyBytes(byte[] log, int start, int len, int p) throws WalException {
        if (len < MIN_KEY_LEN || len > MAX_KEY_LEN) {
            throw new WalException(Code.BAD_KEY, p);
        }
        int end = start + len;
        for (int i = start; i < end; i++) {
            int b = log[i] & 0xFF;
            if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                throw new WalException(Code.BAD_KEY, p);
            }
        }
    }

    private static void checkValueBytes(byte[] log, int start, int len, int p) throws WalException {
        int end = start + len;
        for (int i = start; i < end; i++) {
            int b = log[i] & 0xFF;
            if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                throw new WalException(Code.BAD_VALUE, p);
            }
        }
    }

    private static String asciiString(byte[] log, int start, int len) {
        if (len == 0) return "";
        return new String(log, start, len, StandardCharsets.ISO_8859_1);
    }

    private static boolean equalsAscii(String str, byte[] log, int start, int len) {
        if (str.length() != len) return false;
        for (int i = 0; i < len; i++) {
            if (str.charAt(i) != (log[start + i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static int findOpenTxn(Txn[] openTxns, int openCount, long txnId, int p) throws WalException {
        for (int i = 0; i < openCount; i++) {
            if (openTxns[i].id == txnId) {
                return i;
            }
        }
        throw new WalException(Code.TXN_NOT_OPEN, p);
    }

    private static void closeOpenTxn(Txn[] openTxns, int idx, int openCount) {
        int last = openCount - 1;
        if (idx != last) {
            Txn closed = openTxns[idx];
            openTxns[idx] = openTxns[last];
            openTxns[last] = closed;
        }
    }

    private static final class Txn {
        long id;
        TreapNode snapshot;
        int beginCommitIdx;

        final String[] writeKeys = new String[MAX_WRITES];
        final String[] writeVals = new String[MAX_WRITES]; // null means DELETE
        int writeCount;

        int spCount;
        int[] spNames = new int[8];
        int[] spPositions = new int[8];
        int[] spOffsets = new int[8];

        Set<String> readSet;
        List<String> rangeLos;
        List<String> rangeHis;

        final String[] tempDistinctKeys = new String[MAX_WRITES];

        void init(long id, TreapNode snapshot, int beginCommitIdx) {
            this.id = id;
            this.snapshot = snapshot;
            this.beginCommitIdx = beginCommitIdx;
            this.writeCount = 0;
            this.spCount = 0;
            if (this.readSet != null) this.readSet.clear();
            if (this.rangeLos != null) {
                this.rangeLos.clear();
                this.rangeHis.clear();
            }
        }

        void clear() {
            this.snapshot = null;
            Arrays.fill(this.writeKeys, 0, this.writeCount, null);
            Arrays.fill(this.writeVals, 0, this.writeCount, null);
            this.writeCount = 0;
            this.spCount = 0;
            if (this.readSet != null) this.readSet.clear();
            if (this.rangeLos != null) {
                this.rangeLos.clear();
                this.rangeHis.clear();
            }
        }

        void addWrite(String key, String val) {
            writeKeys[writeCount] = key;
            writeVals[writeCount] = val;
            writeCount++;
        }

        void setSavepoint(int name, int offset) {
            for (int i = 0; i < spCount; i++) {
                if (spNames[i] == name) {
                    spPositions[i] = writeCount;
                    spOffsets[i] = offset;
                    return;
                }
            }
            if (spCount == spNames.length) {
                int newCap = spNames.length * 2;
                spNames = Arrays.copyOf(spNames, newCap);
                spPositions = Arrays.copyOf(spPositions, newCap);
                spOffsets = Arrays.copyOf(spOffsets, newCap);
            }
            spNames[spCount] = name;
            spPositions[spCount] = writeCount;
            spOffsets[spCount] = offset;
            spCount++;
        }

        boolean hasSavepoint(int name) {
            for (int i = 0; i < spCount; i++) {
                if (spNames[i] == name) {
                    return true;
                }
            }
            return false;
        }

        void rollbackTo(int name) {
            int targetPos = -1;
            int targetOffset = -1;
            for (int i = 0; i < spCount; i++) {
                if (spNames[i] == name) {
                    targetPos = spPositions[i];
                    targetOffset = spOffsets[i];
                    break;
                }
            }
            for (int i = targetPos; i < writeCount; i++) {
                writeKeys[i] = null;
                writeVals[i] = null;
            }
            writeCount = targetPos;

            int newSpCount = 0;
            for (int i = 0; i < spCount; i++) {
                if (spNames[i] == name || spOffsets[i] <= targetOffset) {
                    spNames[newSpCount] = spNames[i];
                    spPositions[newSpCount] = spPositions[i];
                    spOffsets[newSpCount] = spOffsets[i];
                    newSpCount++;
                }
            }
            spCount = newSpCount;
        }

        String getView(String key) {
            for (int i = writeCount - 1; i >= 0; i--) {
                if (writeKeys[i].equals(key)) {
                    return writeVals[i];
                }
            }
            return PersistentTreap.get(snapshot, key);
        }

        int countRangeView(String lo, String hi) {
            if (lo.compareTo(hi) > 0) {
                return 0;
            }
            int count = PersistentTreap.countRange(snapshot, lo, hi);
            if (writeCount == 0) {
                return count;
            }
            int distinct = 0;
            for (int i = writeCount - 1; i >= 0; i--) {
                String k = writeKeys[i];
                boolean seen = false;
                for (int j = 0; j < distinct; j++) {
                    if (tempDistinctKeys[j].equals(k)) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    tempDistinctKeys[distinct++] = k;
                    if (k.compareTo(lo) >= 0 && k.compareTo(hi) <= 0) {
                        boolean inSnap = PersistentTreap.get(snapshot, k) != null;
                        boolean inWrite = writeVals[i] != null;
                        if (inWrite && !inSnap) {
                            count++;
                        } else if (!inWrite && inSnap) {
                            count--;
                        }
                    }
                }
            }
            Arrays.fill(tempDistinctKeys, 0, distinct, null);
            return count;
        }

        void addReadKey(String key) {
            if (readSet == null) {
                readSet = new HashSet<>(8);
            }
            readSet.add(key);
        }

        void addReadRange(String lo, String hi) {
            if (rangeLos == null) {
                rangeLos = new ArrayList<>(4);
                rangeHis = new ArrayList<>(4);
            }
            rangeLos.add(lo);
            rangeHis.add(hi);
        }

        boolean hasConflict(CommittedLog log) {
            boolean hasReads = readSet != null && !readSet.isEmpty();
            boolean hasRanges = rangeLos != null && !rangeLos.isEmpty();
            if (!hasReads && !hasRanges) {
                return false;
            }
            int totalCommits = log.commitCount;
            if (beginCommitIdx >= totalCommits) {
                return false;
            }
            int startKeyIdx = log.commitOffsets[beginCommitIdx];
            int endKeyIdx = log.commitOffsets[totalCommits];
            String[] committedKeys = log.keys;
            int rangeCount = hasRanges ? rangeLos.size() : 0;

            for (int idx = startKeyIdx; idx < endKeyIdx; idx++) {
                String writtenKey = committedKeys[idx];
                if (hasReads && readSet.contains(writtenKey)) {
                    return true;
                }
                if (hasRanges) {
                    for (int r = 0; r < rangeCount; r++) {
                        String lo = rangeLos.get(r);
                        String hi = rangeHis.get(r);
                        if (writtenKey.compareTo(lo) >= 0 && writtenKey.compareTo(hi) <= 0) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    private static final class CommittedLog {
        int[] commitOffsets = new int[1024];
        int commitCount = 0;
        String[] keys = new String[2048];
        int keyCount = 0;

        CommittedLog() {
            commitOffsets[0] = 0;
        }

        void beginCommit() {
            ensureCommitCap();
        }

        void addKey(String key) {
            if (keyCount == keys.length) {
                keys = Arrays.copyOf(keys, keys.length * 2);
            }
            keys[keyCount++] = key;
        }

        void endCommit() {
            commitCount++;
            commitOffsets[commitCount] = keyCount;
        }

        void recordEmptyCommit() {
            ensureCommitCap();
            commitCount++;
            commitOffsets[commitCount] = keyCount;
        }

        private void ensureCommitCap() {
            if (commitCount + 1 >= commitOffsets.length) {
                commitOffsets = Arrays.copyOf(commitOffsets, commitOffsets.length * 2);
            }
        }
    }

    private static final class TreapNode {
        final String key;
        final String val;
        final int prio;
        final int size;
        final TreapNode left;
        final TreapNode right;

        TreapNode(String key, String val, int prio, TreapNode left, TreapNode right) {
            this.key = key;
            this.val = val;
            this.prio = prio;
            this.left = left;
            this.right = right;
            this.size = 1 + (left == null ? 0 : left.size) + (right == null ? 0 : right.size);
        }
    }

    private static final class PersistentTreap {
        TreapNode root;
        private int rngState = 0x9E3779B9; // deterministic xorshift seed

        private int nextPrio() {
            int x = rngState;
            x ^= (x << 13);
            x ^= (x >>> 17);
            x ^= (x << 5);
            if (x == 0) x = 1;
            rngState = x;
            return x;
        }

        void put(String key, String val) {
            String existing = get(root, key);
            if (val.equals(existing)) {
                return;
            }
            if (existing != null) {
                root = replaceValue(root, key, val);
            } else {
                root = insertNew(root, key, val, nextPrio());
            }
        }

        void remove(String key) {
            if (get(root, key) == null) {
                return;
            }
            root = deleteExisting(root, key);
        }

        static String get(TreapNode node, String key) {
            while (node != null) {
                int cmp = key.compareTo(node.key);
                if (cmp == 0) return node.val;
                node = cmp < 0 ? node.left : node.right;
            }
            return null;
        }

        static int countRange(TreapNode node, String lo, String hi) {
            if (node == null) return 0;
            return countLessOrEqual(node, hi) - countStrictlyLess(node, lo);
        }

        private static int countStrictlyLess(TreapNode node, String bound) {
            int count = 0;
            while (node != null) {
                int cmp = node.key.compareTo(bound);
                if (cmp < 0) {
                    count += 1 + (node.left == null ? 0 : node.left.size);
                    node = node.right;
                } else {
                    node = node.left;
                }
            }
            return count;
        }

        private static int countLessOrEqual(TreapNode node, String bound) {
            int count = 0;
            while (node != null) {
                int cmp = node.key.compareTo(bound);
                if (cmp <= 0) {
                    count += 1 + (node.left == null ? 0 : node.left.size);
                    node = node.right;
                } else {
                    node = node.left;
                }
            }
            return count;
        }

        private static TreapNode replaceValue(TreapNode node, String key, String val) {
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                return new TreapNode(node.key, val, node.prio, node.left, node.right);
            } else if (cmp < 0) {
                return new TreapNode(node.key, node.val, node.prio, replaceValue(node.left, key, val), node.right);
            } else {
                return new TreapNode(node.key, node.val, node.prio, node.left, replaceValue(node.right, key, val));
            }
        }

        private static TreapNode insertNew(TreapNode node, String key, String val, int prio) {
            if (node == null) {
                return new TreapNode(key, val, prio, null, null);
            }
            int cmp = key.compareTo(node.key);
            if (cmp < 0) {
                TreapNode nl = insertNew(node.left, key, val, prio);
                if (nl.prio > node.prio) {
                    TreapNode nr = new TreapNode(node.key, node.val, node.prio, nl.right, node.right);
                    return new TreapNode(nl.key, nl.val, nl.prio, nl.left, nr);
                }
                return new TreapNode(node.key, node.val, node.prio, nl, node.right);
            } else {
                TreapNode nr = insertNew(node.right, key, val, prio);
                if (nr.prio > node.prio) {
                    TreapNode nl = new TreapNode(node.key, node.val, node.prio, node.left, nr.left);
                    return new TreapNode(nr.key, nr.val, nr.prio, nl, nr.right);
                }
                return new TreapNode(node.key, node.val, node.prio, node.left, nr);
            }
        }

        private static TreapNode deleteExisting(TreapNode node, String key) {
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                return merge(node.left, node.right);
            } else if (cmp < 0) {
                return new TreapNode(node.key, node.val, node.prio, deleteExisting(node.left, key), node.right);
            } else {
                return new TreapNode(node.key, node.val, node.prio, node.left, deleteExisting(node.right, key));
            }
        }

        private static TreapNode merge(TreapNode left, TreapNode right) {
            if (left == null) return right;
            if (right == null) return left;
            if (left.prio > right.prio) {
                return new TreapNode(left.key, left.val, left.prio, left.left, merge(left.right, right));
            } else {
                return new TreapNode(right.key, right.val, right.prio, merge(left, right.left), right.right);
            }
        }

        void collectToMap(Map<String, String> out) {
            collectRec(root, out);
        }

        private static void collectRec(TreapNode node, Map<String, String> out) {
            if (node == null) return;
            collectRec(node.left, out);
            out.put(node.key, node.val);
            collectRec(node.right, out);
        }
    }

    private static final class IntHashSet {
        private int[] table;
        private int mask;
        private int size;
        private int threshold;

        IntHashSet(int initialCapacity) {
            int cap = Integer.highestOneBit(Math.max(16, initialCapacity - 1)) << 1;
            table = new int[cap];
            mask = cap - 1;
            threshold = (int) (cap * 0.7f);
        }

        boolean contains(int key) {
            // key != 0 always holds for valid txn ids (R14 checks txnId != 0)
            int idx = mix(key) & mask;
            while (true) {
                int cur = table[idx];
                if (cur == 0) return false;
                if (cur == key) return true;
                idx = (idx + 1) & mask;
            }
        }

        void add(int key) {
            if (size >= threshold) {
                rehash();
            }
            int idx = mix(key) & mask;
            while (true) {
                int cur = table[idx];
                if (cur == 0) {
                    table[idx] = key;
                    size++;
                    return;
                }
                if (cur == key) {
                    return;
                }
                idx = (idx + 1) & mask;
            }
        }

        private void rehash() {
            int newCap = table.length << 1;
            int[] newTable = new int[newCap];
            int newMask = newCap - 1;
            for (int key : table) {
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
            threshold = (int) (newCap * 0.7f);
        }

        private static int mix(int x) {
            x ^= (x >>> 16);
            x *= 0x85ebca6b;
            x ^= (x >>> 13);
            x *= 0xc2b2ae35;
            x ^= (x >>> 16);
            return x;
        }
    }

    private static final class LongArray {
        private long[] data = new long[64];
        private int size = 0;

        void add(long v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = v;
        }

        List<Long> toList() {
            if (size == 0) return Collections.emptyList();
            List<Long> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(data[i]);
            }
            return list;
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
