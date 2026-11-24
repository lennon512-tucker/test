import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        // R1
        if (log == null) {
            throw new NullPointerException();
        }
        final int L = log.length;
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
        int p = HEADER_SIZE;
        boolean tornTail = false;
        while (true) {
            // R5
            if (p == L) {
                break;
            }
            // R6
            if (L - p <= 2) {
                tornTail = true;
                break;
            }
            // R7
            int type = log[p] & 0xFF;
            if (type < 1 || type > 9) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }
            // R8
            int N = readU16(log, p + 1);
            if (N > MAX_PAYLOAD) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }
            // R9
            int end = p + 5 + N;
            if (end > L) {
                tornTail = true;
                break;
            }
            // R10
            int s1 = 0;
            int s2 = 0;
            int checksumEnd = p + 3 + N;
            for (int i = p; i < checksumEnd; i++) {
                s1 += (log[i] & 0xFF);
                s2 += s1;
            }
            s1 %= 255;
            s2 %= 255;
            int storedS2 = log[checksumEnd] & 0xFF;
            int storedS1 = log[checksumEnd + 1] & 0xFF;
            if (storedS2 != s2 || storedS1 != s1) {
                if (end == L) {
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }
            // R11
            p = end;
        }
        final int validLength = p;

        // Phase 2: replay
        IntHashSet seenTxns = new IntHashSet(1024);
        Txn[] openPool = new Txn[MAX_OPEN_TXNS];
        for (int i = 0; i < MAX_OPEN_TXNS; i++) {
            openPool[i] = new Txn();
        }
        Txn[] openTxns = new Txn[MAX_OPEN_TXNS];
        int openCount = 0;

        KeyInterner keyInterner = new KeyInterner(512);
        int keyCap = 512;
        boolean[] currentPresent = new boolean[keyCap];
        int[] currentValOff = new int[keyCap];
        int[] currentValLen = new int[keyCap];
        int[] lastCommitWriteOffset = new int[keyCap];

        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();

        long[] tempIds = new long[MAX_OPEN_TXNS];

        int pos = HEADER_SIZE;
        while (pos < validLength) {
            int type = log[pos] & 0xFF;
            int N = readU16(log, pos + 1);
            int payloadOff = pos + 3;

            long txnId = 0;
            int k = 0;
            int keyOff = 0;
            int v = 0;
            int valOff = 0;
            int f = 0;
            int c = 0;
            int spName = 0;

            // R13: Payload layout
            switch (type) {
                case 1: // BEGIN
                case 4: // COMMIT
                case 5: // ABORT
                    if (N != 4) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                    }
                    txnId = readU32(log, payloadOff);
                    break;
                case 2: // PUT
                    if (N < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                    }
                    k = log[payloadOff + 4] & 0xFF;
                    if (N < 7 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                    }
                    v = readU16(log, payloadOff + 5 + k);
                    if (N != 7 + k + v) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                    }
                    txnId = readU32(log, payloadOff);
                    keyOff = payloadOff + 5;
                    valOff = payloadOff + 7 + k;
                    break;
                case 3: // DELETE
                    if (N < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                    }
                    k = log[payloadOff + 4] & 0xFF;
                    if (N != 5 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                    }
                    txnId = readU32(log, payloadOff);
                    keyOff = payloadOff + 5;
                    break;
                case 6: // SAVEPOINT
                case 7: // ROLLBACK
                    if (N != 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                    }
                    txnId = readU32(log, payloadOff);
                    spName = log[payloadOff + 4] & 0xFF;
                    break;
                case 8: // CHECKPOINT
                    if (N < 1) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                    }
                    c = log[payloadOff] & 0xFF;
                    if (N != 1 + 4 * c) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                    }
                    break;
                case 9: // READ
                    if (N < 6) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                    }
                    k = log[payloadOff + 4] & 0xFF;
                    if (N < 6 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                    }
                    f = log[payloadOff + 5 + k] & 0xFF;
                    if (f == 0) {
                        if (N != 6 + k) {
                            throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                        }
                    } else if (f == 1) {
                        if (N < 8 + k) {
                            throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                        }
                        v = readU16(log, payloadOff + 6 + k);
                        if (N != 8 + k + v) {
                            throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                        }
                        valOff = payloadOff + 8 + k;
                    } else {
                        throw new WalException(Code.MALFORMED_PAYLOAD, pos);
                    }
                    txnId = readU32(log, payloadOff);
                    keyOff = payloadOff + 5;
                    break;
            }

            // R14: Txn ids
            if (type == 8) {
                for (int i = 0; i < c; i++) {
                    if (readU32(log, payloadOff + 1 + 4 * i) == 0) {
                        throw new WalException(Code.BAD_TXN_ID, pos);
                    }
                }
            } else {
                if (txnId == 0) {
                    throw new WalException(Code.BAD_TXN_ID, pos);
                }
            }

            // R15: Key (PUT, DELETE, READ)
            if (type == 2 || type == 3 || type == 9) {
                if (k < MIN_KEY_LEN || k > MAX_KEY_LEN) {
                    throw new WalException(Code.BAD_KEY, pos);
                }
                for (int i = 0; i < k; i++) {
                    int b = log[keyOff + i] & 0xFF;
                    if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                        throw new WalException(Code.BAD_KEY, pos);
                    }
                }
            }

            // R16: Value (PUT, and READ when f = 1)
            if (type == 2 || (type == 9 && f == 1)) {
                for (int i = 0; i < v; i++) {
                    int b = log[valOff + i] & 0xFF;
                    if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                        throw new WalException(Code.BAD_VALUE, pos);
                    }
                }
            }

            // R16a: Name (SAVEPOINT, ROLLBACK)
            if (type == 6 || type == 7) {
                if (spName < NAME_BYTE_MIN || spName > NAME_BYTE_MAX) {
                    throw new WalException(Code.BAD_NAME, pos);
                }
            }

            // Transaction checks and actions
            switch (type) {
                case 1: { // BEGIN
                    // R17: Reused id
                    if (seenTxns.contains((int) txnId)) {
                        throw new WalException(Code.DUPLICATE_TXN, pos);
                    }
                    // R18: Open limit
                    if (openCount == MAX_OPEN_TXNS) {
                        throw new WalException(Code.TOO_MANY_OPEN, pos);
                    }
                    // R20: BEGIN action
                    seenTxns.add((int) txnId);
                    Txn t = openPool[openCount];
                    t.reset(txnId, pos);
                    openTxns[openCount++] = t;
                    break;
                }
                case 2: // PUT
                case 3: { // DELETE
                    // R19: Open check
                    Txn t = findOpen(openTxns, openCount, txnId);
                    if (t == null) {
                        throw new WalException(Code.TXN_NOT_OPEN, pos);
                    }
                    // R19a: Write limit
                    if (t.writeCount == MAX_WRITES) {
                        throw new WalException(Code.TXN_TOO_LARGE, pos);
                    }
                    // R21: PUT and DELETE action
                    int keyId = keyInterner.intern(log, keyOff, k);
                    if (keyId >= keyCap) {
                        int newCap = keyCap << 1;
                        currentPresent = Arrays.copyOf(currentPresent, newCap);
                        currentValOff = Arrays.copyOf(currentValOff, newCap);
                        currentValLen = Arrays.copyOf(currentValLen, newCap);
                        lastCommitWriteOffset = Arrays.copyOf(lastCommitWriteOffset, newCap);
                        keyCap = newCap;
                    }
                    int wc = t.writeCount++;
                    t.writeKeyIds[wc] = keyId;
                    if (type == 2) {
                        t.writeIsPut[wc] = true;
                        t.writeValOff[wc] = valOff;
                        t.writeValLen[wc] = v;
                    } else {
                        t.writeIsPut[wc] = false;
                    }
                    break;
                }
                case 4: { // COMMIT
                    // R19: Open check
                    int idx = findOpenIdx(openTxns, openCount, txnId);
                    if (idx < 0) {
                        throw new WalException(Code.TXN_NOT_OPEN, pos);
                    }
                    Txn t = openTxns[idx];
                    removeOpenAt(openTxns, openPool, idx, openCount--);

                    // R22: COMMIT action
                    boolean conflict = false;
                    for (int i = 0; i < t.readCount; i++) {
                        if (lastCommitWriteOffset[t.readKeys[i]] > t.beginOffset) {
                            conflict = true;
                            break;
                        }
                    }

                    if (conflict) {
                        aborted.add(txnId);
                    } else {
                        for (int w = 0; w < t.writeCount; w++) {
                            int kid = t.writeKeyIds[w];
                            boolean isPut = t.writeIsPut[w];
                            int wOff = isPut ? t.writeValOff[w] : 0;
                            int wLen = isPut ? t.writeValLen[w] : 0;

                            // Preserve snapshot of open transactions before first overwrite
                            for (int oi = 0; oi < openCount; oi++) {
                                openTxns[oi].saveBeforeOverwrite(kid, currentPresent[kid], currentValOff[kid], currentValLen[kid]);
                            }

                            if (isPut) {
                                currentPresent[kid] = true;
                                currentValOff[kid] = wOff;
                                currentValLen[kid] = wLen;
                            } else {
                                currentPresent[kid] = false;
                            }
                            lastCommitWriteOffset[kid] = pos;
                        }
                        committed.add(txnId);
                    }
                    break;
                }
                case 5: { // ABORT
                    // R19: Open check
                    int idx = findOpenIdx(openTxns, openCount, txnId);
                    if (idx < 0) {
                        throw new WalException(Code.TXN_NOT_OPEN, pos);
                    }
                    removeOpenAt(openTxns, openPool, idx, openCount--);
                    // R23: ABORT action
                    aborted.add(txnId);
                    break;
                }
                case 6: { // SAVEPOINT
                    // R19: Open check
                    Txn t = findOpen(openTxns, openCount, txnId);
                    if (t == null) {
                        throw new WalException(Code.TXN_NOT_OPEN, pos);
                    }
                    // R23a: SAVEPOINT action
                    int spIdx = spName - NAME_BYTE_MIN;
                    t.spPos[spIdx] = t.writeCount;
                    t.spSetOffset[spIdx] = pos;
                    break;
                }
                case 7: { // ROLLBACK
                    // R19: Open check
                    Txn t = findOpen(openTxns, openCount, txnId);
                    if (t == null) {
                        throw new WalException(Code.TXN_NOT_OPEN, pos);
                    }
                    int spIdx = spName - NAME_BYTE_MIN;
                    int targetSetOff = t.spSetOffset[spIdx];
                    // R19b: Savepoint check
                    if (targetSetOff == 0) {
                        throw new WalException(Code.NO_SAVEPOINT, pos);
                    }
                    // R23b: ROLLBACK action
                    t.writeCount = t.spPos[spIdx];
                    for (int i = 0; i < Txn.NUM_SP; i++) {
                        if (t.spSetOffset[i] > targetSetOff) {
                            t.spSetOffset[i] = 0;
                        }
                    }
                    break;
                }
                case 8: { // CHECKPOINT
                    // R19c: Checkpoint check
                    if (c != openCount) {
                        throw new WalException(Code.CHECKPOINT_MISMATCH, pos);
                    }
                    if (c > 0) {
                        for (int i = 0; i < openCount; i++) {
                            tempIds[i] = openTxns[i].id;
                        }
                        Arrays.sort(tempIds, 0, openCount);
                        for (int i = 0; i < c; i++) {
                            long listed = readU32(log, payloadOff + 1 + 4 * i);
                            if (listed != tempIds[i]) {
                                throw new WalException(Code.CHECKPOINT_MISMATCH, pos);
                            }
                        }
                    }
                    // R23c: CHECKPOINT action (no-op)
                    break;
                }
                case 9: { // READ
                    // R19: Open check
                    Txn t = findOpen(openTxns, openCount, txnId);
                    if (t == null) {
                        throw new WalException(Code.TXN_NOT_OPEN, pos);
                    }
                    // R19d: Read check
                    int keyId = keyInterner.intern(log, keyOff, k);
                    if (keyId >= keyCap) {
                        int newCap = keyCap << 1;
                        currentPresent = Arrays.copyOf(currentPresent, newCap);
                        currentValOff = Arrays.copyOf(currentValOff, newCap);
                        currentValLen = Arrays.copyOf(currentValLen, newCap);
                        lastCommitWriteOffset = Arrays.copyOf(lastCommitWriteOffset, newCap);
                        keyCap = newCap;
                    }

                    boolean viewPresent = false;
                    int viewOff = 0;
                    int viewLen = 0;
                    boolean decided = false;

                    for (int w = t.writeCount - 1; w >= 0; w--) {
                        if (t.writeKeyIds[w] == keyId) {
                            decided = true;
                            if (t.writeIsPut[w]) {
                                viewPresent = true;
                                viewOff = t.writeValOff[w];
                                viewLen = t.writeValLen[w];
                            } else {
                                viewPresent = false;
                            }
                            break;
                        }
                    }

                    if (!decided) {
                        int snapSlot = t.findSnapshotSlot(keyId);
                        if (snapSlot >= 0) {
                            viewPresent = t.snapPresent[snapSlot];
                            viewOff = t.snapValOff[snapSlot];
                            viewLen = t.snapValLen[snapSlot];
                        } else {
                            viewPresent = currentPresent[keyId];
                            viewOff = currentValOff[keyId];
                            viewLen = currentValLen[keyId];
                        }
                    }

                    if (f == 0) {
                        if (viewPresent) {
                            throw new WalException(Code.READ_MISMATCH, pos);
                        }
                    } else {
                        if (!viewPresent || viewLen != v || !Arrays.equals(log, viewOff, viewOff + viewLen, log, valOff, valOff + v)) {
                            throw new WalException(Code.READ_MISMATCH, pos);
                        }
                    }

                    // R23d: READ action
                    t.addReadKey(keyId);
                    break;
                }
            }

            pos += 5 + N;
        }

        // Build Result (R24 - R27)
        int totalKeys = keyInterner.size();
        int activeCount = 0;
        for (int i = 0; i < totalKeys; i++) {
            if (currentPresent[i]) {
                activeCount++;
            }
        }
        Map<String, String> state = new HashMap<>(Math.max(16, (int) (activeCount / 0.75f) + 1));
        for (int i = 0; i < totalKeys; i++) {
            if (currentPresent[i]) {
                String kStr = keyInterner.getString(log, i);
                int vLen = currentValLen[i];
                String vStr = vLen == 0 ? "" : new String(log, currentValOff[i], vLen, StandardCharsets.ISO_8859_1);
                state.put(kStr, vStr);
            }
        }

        List<Long> incomplete;
        if (openCount == 0) {
            incomplete = Collections.emptyList();
        } else {
            for (int i = 0; i < openCount; i++) {
                tempIds[i] = openTxns[i].id;
            }
            Arrays.sort(tempIds, 0, openCount);
            incomplete = new ArrayList<>(openCount);
            for (int i = 0; i < openCount; i++) {
                incomplete.add(tempIds[i]);
            }
        }

        return new Result(
                Collections.unmodifiableMap(state),
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

    private static int readU16(byte[] log, int offset) {
        return ((log[offset] & 0xFF) << 8) | (log[offset + 1] & 0xFF);
    }

    private static long readU32(byte[] log, int offset) {
        return ((long) (log[offset] & 0xFF) << 24)
                | ((long) (log[offset + 1] & 0xFF) << 16)
                | ((long) (log[offset + 2] & 0xFF) << 8)
                | ((long) (log[offset + 3] & 0xFF));
    }

    private static Txn findOpen(Txn[] openTxns, int openCount, long txnId) {
        for (int i = 0; i < openCount; i++) {
            if (openTxns[i].id == txnId) {
                return openTxns[i];
            }
        }
        return null;
    }

    private static int findOpenIdx(Txn[] openTxns, int openCount, long txnId) {
        for (int i = 0; i < openCount; i++) {
            if (openTxns[i].id == txnId) {
                return i;
            }
        }
        return -1;
    }

    private static void removeOpenAt(Txn[] openTxns, Txn[] openPool, int idx, int openCount) {
        Txn removed = openTxns[idx];
        int last = openCount - 1;
        openTxns[idx] = openTxns[last];
        openTxns[last] = null;
        openPool[last] = removed;
    }

    private static final class Txn {
        static final int NUM_SP = NAME_BYTE_MAX - NAME_BYTE_MIN + 1; // 94

        long id;
        int beginOffset;

        int writeCount;
        final int[] writeKeyIds = new int[MAX_WRITES];
        final boolean[] writeIsPut = new boolean[MAX_WRITES];
        final int[] writeValOff = new int[MAX_WRITES];
        final int[] writeValLen = new int[MAX_WRITES];

        final int[] spPos = new int[NUM_SP];
        final int[] spSetOffset = new int[NUM_SP];

        int readCount;
        int[] readKeys = new int[16];
        int[] readTable = new int[32];

        int snapCount;
        int[] snapTable = new int[32];
        int[] snapSlotData = new int[16];
        boolean[] snapPresent = new boolean[16];
        int[] snapValOff = new int[16];
        int[] snapValLen = new int[16];

        void reset(long id, int beginOffset) {
            this.id = id;
            this.beginOffset = beginOffset;
            this.writeCount = 0;
            Arrays.fill(spSetOffset, 0);

            if (readCount > 0) {
                if (readTable.length > 64) {
                    readTable = new int[32];
                } else {
                    Arrays.fill(readTable, 0);
                }
                readCount = 0;
            }

            if (snapCount > 0) {
                if (snapTable.length > 64) {
                    snapTable = new int[32];
                } else {
                    Arrays.fill(snapTable, 0);
                }
                snapCount = 0;
            }
        }

        void addReadKey(int keyId) {
            int k = keyId + 1;
            int len = readTable.length;
            int mask = len - 1;
            int slot = mix(k) & mask;
            while (true) {
                int cur = readTable[slot];
                if (cur == 0) {
                    readTable[slot] = k;
                    if (readCount == readKeys.length) {
                        readKeys = Arrays.copyOf(readKeys, readKeys.length << 1);
                    }
                    readKeys[readCount++] = keyId;
                    if (readCount * 2 >= len) {
                        rehashReads();
                    }
                    return;
                }
                if (cur == k) {
                    return;
                }
                slot = (slot + 1) & mask;
            }
        }

        private void rehashReads() {
            int newLen = readTable.length << 1;
            int[] newTable = new int[newLen];
            int mask = newLen - 1;
            for (int i = 0; i < readCount; i++) {
                int k = readKeys[i] + 1;
                int slot = mix(k) & mask;
                while (newTable[slot] != 0) {
                    slot = (slot + 1) & mask;
                }
                newTable[slot] = k;
            }
            readTable = newTable;
        }

        void saveBeforeOverwrite(int keyId, boolean wasPresent, int oldOff, int oldLen) {
            int k = keyId + 1;
            int len = snapTable.length;
            int mask = len - 1;
            int slot = mix(k) & mask;
            while (true) {
                int entry = snapTable[slot];
                if (entry == 0) {
                    int idx = snapCount++;
                    if (idx == snapPresent.length) {
                        int newCap = snapPresent.length << 1;
                        snapPresent = Arrays.copyOf(snapPresent, newCap);
                        snapValOff = Arrays.copyOf(snapValOff, newCap);
                        snapValLen = Arrays.copyOf(snapValLen, newCap);
                        snapSlotData = Arrays.copyOf(snapSlotData, newCap);
                    }
                    snapPresent[idx] = wasPresent;
                    snapValOff[idx] = oldOff;
                    snapValLen[idx] = oldLen;
                    snapSlotData[idx] = k;
                    snapTable[slot] = idx + 1;
                    if (snapCount * 2 >= len) {
                        rehashSnaps();
                    }
                    return;
                }
                if (snapSlotData[entry - 1] == k) {
                    return; // already saved original value at BEGIN
                }
                slot = (slot + 1) & mask;
            }
        }

        int findSnapshotSlot(int keyId) {
            if (snapCount == 0) {
                return -1;
            }
            int k = keyId + 1;
            int mask = snapTable.length - 1;
            int slot = mix(k) & mask;
            while (true) {
                int entry = snapTable[slot];
                if (entry == 0) {
                    return -1;
                }
                if (snapSlotData[entry - 1] == k) {
                    return entry - 1;
                }
                slot = (slot + 1) & mask;
            }
        }

        private void rehashSnaps() {
            int newLen = snapTable.length << 1;
            int[] newTable = new int[newLen];
            int mask = newLen - 1;
            for (int i = 0; i < snapCount; i++) {
                int k = snapSlotData[i];
                int slot = mix(k) & mask;
                while (newTable[slot] != 0) {
                    slot = (slot + 1) & mask;
                }
                newTable[slot] = i + 1;
            }
            snapTable = newTable;
        }

        private static int mix(int x) {
            x ^= x >>> 16;
            x *= 0x85ebca6b;
            x ^= x >>> 13;
            x *= 0xc2b2ae35;
            x ^= x >>> 16;
            return x;
        }
    }

    private static final class IntHashSet {
        private int[] table;
        private boolean[] occupied;
        private int mask;
        private int size;
        private int threshold;

        IntHashSet(int initialCap) {
            int cap = Integer.highestOneBit(Math.max(16, initialCap) - 1) << 1;
            table = new int[cap];
            occupied = new boolean[cap];
            mask = cap - 1;
            threshold = (cap * 2) / 3;
        }

        boolean contains(int key) {
            int slot = mix(key) & mask;
            while (occupied[slot]) {
                if (table[slot] == key) {
                    return true;
                }
                slot = (slot + 1) & mask;
            }
            return false;
        }

        void add(int key) {
            int slot = mix(key) & mask;
            while (occupied[slot]) {
                if (table[slot] == key) {
                    return;
                }
                slot = (slot + 1) & mask;
            }
            occupied[slot] = true;
            table[slot] = key;
            if (++size >= threshold) {
                rehash();
            }
        }

        private void rehash() {
            int newCap = table.length << 1;
            int[] newTable = new int[newCap];
            boolean[] newOcc = new boolean[newCap];
            int newMask = newCap - 1;
            int oldCap = table.length;
            for (int i = 0; i < oldCap; i++) {
                if (occupied[i]) {
                    int k = table[i];
                    int slot = mix(k) & newMask;
                    while (newOcc[slot]) {
                        slot = (slot + 1) & newMask;
                    }
                    newOcc[slot] = true;
                    newTable[slot] = k;
                }
            }
            table = newTable;
            occupied = newOcc;
            mask = newMask;
            threshold = (newCap * 2) / 3;
        }

        private static int mix(int x) {
            x ^= x >>> 16;
            x *= 0x85ebca6b;
            x ^= x >>> 13;
            x *= 0xc2b2ae35;
            x ^= x >>> 16;
            return x;
        }
    }

    private static final class KeyInterner {
        private int[] table;
        private int mask;
        private int threshold;
        private int count;

        private int[] keyOffs;
        private byte[] keyLens;
        private int[] keyHashes;

        KeyInterner(int initialCap) {
            int cap = Integer.highestOneBit(Math.max(16, initialCap) - 1) << 1;
            table = new int[cap];
            mask = cap - 1;
            threshold = (cap * 2) / 3;

            int arrayCap = cap >> 1;
            keyOffs = new int[arrayCap];
            keyLens = new byte[arrayCap];
            keyHashes = new int[arrayCap];
        }

        int size() {
            return count;
        }

        int intern(byte[] log, int off, int len) {
            int h = hashBytes(log, off, len);
            int slot = h & mask;
            while (true) {
                int entry = table[slot];
                if (entry == 0) {
                    int id = count++;
                    if (id == keyOffs.length) {
                        int newArrayCap = keyOffs.length << 1;
                        keyOffs = Arrays.copyOf(keyOffs, newArrayCap);
                        keyLens = Arrays.copyOf(keyLens, newArrayCap);
                        keyHashes = Arrays.copyOf(keyHashes, newArrayCap);
                    }
                    keyOffs[id] = off;
                    keyLens[id] = (byte) len;
                    keyHashes[id] = h;
                    table[slot] = id + 1;
                    if (count >= threshold) {
                        rehash();
                    }
                    return id;
                }
                int id = entry - 1;
                if (keyHashes[id] == h && (keyLens[id] & 0xFF) == len) {
                    int existingOff = keyOffs[id];
                    boolean match = true;
                    for (int i = 0; i < len; i++) {
                        if (log[existingOff + i] != log[off + i]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        return id;
                    }
                }
                slot = (slot + 1) & mask;
            }
        }

        String getString(byte[] log, int id) {
            return new String(log, keyOffs[id], keyLens[id] & 0xFF, StandardCharsets.ISO_8859_1);
        }

        private void rehash() {
            int newCap = table.length << 1;
            int[] newTable = new int[newCap];
            int newMask = newCap - 1;
            for (int id = 0; id < count; id++) {
                int slot = keyHashes[id] & newMask;
                while (newTable[slot] != 0) {
                    slot = (slot + 1) & newMask;
                }
                newTable[slot] = id + 1;
            }
            table = newTable;
            mask = newMask;
            threshold = (newCap * 2) / 3;
        }

        private static int hashBytes(byte[] log, int off, int len) {
            int h = 0x811c9dc5;
            for (int i = 0; i < len; i++) {
                h ^= (log[off + i] & 0xFF);
                h *= 0x01000193;
            }
            h ^= h >>> 16;
            return h;
        }
    }
}
