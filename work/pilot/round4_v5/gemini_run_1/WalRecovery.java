import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WalRecovery {
    private WalRecovery() {}

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        if (log == null) {
            throw new NullPointerException("log is null");
        }

        final int L = log.length;
        if (L == 0) {
            return new Result(Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, false);
        }

        final byte[] MAGIC = {0x57, 0x41, 0x4C, 0x31};
        if (L < 4) {
            for (int i = 0; i < L; i++) {
                if (log[i] != MAGIC[i]) {
                    throw new WalException(Code.BAD_MAGIC, 0);
                }
            }
            return new Result(Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, true);
        }

        if (log[0] != MAGIC[0] || log[1] != MAGIC[1] || log[2] != MAGIC[2] || log[3] != MAGIC[3]) {
            throw new WalException(Code.BAD_MAGIC, 0);
        }

        // Phase 1: frames
        int p = 4;
        int validLength = L;
        boolean tornTail = false;

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
            if (type < 1 || type > 9) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }
            int N = ((log[p + 1] & 0xFF) << 8) | (log[p + 2] & 0xFF);
            if (N > 1024) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }
            int end = p + 5 + N;
            if (end > L) {
                validLength = p;
                tornTail = true;
                break;
            }
            if (!checkFletcher16(log, p, 3 + N)) {
                if (end == L) {
                    validLength = p;
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }
            p = end;
        }

        // Phase 2: replay
        IntSet seenTxns = new IntSet(1024);
        Txn[] pool = new Txn[4];
        for (int i = 0; i < 4; i++) {
            pool[i] = new Txn();
        }
        Txn[] openTxns = new Txn[4];
        int openCount = 0;

        KeyMap currentState = new KeyMap();
        long currentSeq = 0;

        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();

        p = 4;
        while (p < validLength) {
            int type = log[p] & 0xFF;
            int N = ((log[p + 1] & 0xFF) << 8) | (log[p + 2] & 0xFF);
            int payloadStart = p + 3;

            int txnId = 0;
            int k = 0;
            int keyOff = 0;
            int v = 0;
            int valOff = 0;
            int f = 0;
            int name = 0;
            int c = 0;

            // R13: Payload layout
            switch (type) {
                case 1, 4, 5 -> {
                    if (N != 4) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                case 2 -> {
                    if (N < 5) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    k = log[payloadStart + 4] & 0xFF;
                    if (N < 7 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    v = ((log[payloadStart + 5 + k] & 0xFF) << 8) | (log[payloadStart + 6 + k] & 0xFF);
                    if (N != 7 + k + v) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    keyOff = payloadStart + 5;
                    valOff = payloadStart + 7 + k;
                }
                case 3 -> {
                    if (N < 5) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    k = log[payloadStart + 4] & 0xFF;
                    if (N != 5 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    keyOff = payloadStart + 5;
                }
                case 6, 7 -> {
                    if (N != 5) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    name = log[payloadStart + 4] & 0xFF;
                }
                case 8 -> {
                    if (N < 1) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    c = log[payloadStart] & 0xFF;
                    if (N != 1 + 4 * c) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
                case 9 -> {
                    if (N < 6) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    k = log[payloadStart + 4] & 0xFF;
                    if (N < 6 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    f = log[payloadStart + 5 + k] & 0xFF;
                    keyOff = payloadStart + 5;
                    if (f == 0) {
                        if (N != 6 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    } else if (f == 1) {
                        if (N < 8 + k) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                        v = ((log[payloadStart + 6 + k] & 0xFF) << 8) | (log[payloadStart + 7 + k] & 0xFF);
                        if (N != 8 + k + v) throw new WalException(Code.MALFORMED_PAYLOAD, p);
                        valOff = payloadStart + 8 + k;
                    } else {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                }
            }

            // R14: Txn ids
            if (type != 8) {
                txnId = readU32AsInt(log, payloadStart);
                if (txnId == 0) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
            } else {
                for (int i = 0; i < c; i++) {
                    int listedId = readU32AsInt(log, payloadStart + 1 + 4 * i);
                    if (listedId == 0) {
                        throw new WalException(Code.BAD_TXN_ID, p);
                    }
                }
            }

            // R15: Key (PUT=2, DELETE=3, READ=9)
            if (type == 2 || type == 3 || type == 9) {
                if (k < 1 || k > 32) {
                    throw new WalException(Code.BAD_KEY, p);
                }
                for (int i = 0; i < k; i++) {
                    int b = log[keyOff + i] & 0xFF;
                    if (b < 0x21 || b > 0x7E) {
                        throw new WalException(Code.BAD_KEY, p);
                    }
                }
            }

            // R16: Value (PUT=2, and READ=9 when f=1)
            if (type == 2 || (type == 9 && f == 1)) {
                for (int i = 0; i < v; i++) {
                    int b = log[valOff + i] & 0xFF;
                    if (b < 0x20 || b > 0x7E) {
                        throw new WalException(Code.BAD_VALUE, p);
                    }
                }
            }

            // R16a: Name (SAVEPOINT=6, ROLLBACK=7)
            if (type == 6 || type == 7) {
                if (name < 0x21 || name > 0x7E) {
                    throw new WalException(Code.BAD_NAME, p);
                }
            }

            // R17 & R18: BEGIN
            if (type == 1) {
                if (seenTxns.contains(txnId)) {
                    throw new WalException(Code.DUPLICATE_TXN, p);
                }
                if (openCount == 4) {
                    throw new WalException(Code.TOO_MANY_OPEN, p);
                }
                // R20: BEGIN action
                seenTxns.add(txnId);
                Txn t = pool[openCount];
                t.reset(txnId, p, currentSeq);
                openTxns[openCount++] = t;
            } else if (type == 8) {
                // R19c: Checkpoint check
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
                        long listed = Integer.toUnsignedLong(readU32AsInt(log, payloadStart + 1 + 4 * i));
                        if (listed != openIds[i]) {
                            throw new WalException(Code.CHECKPOINT_MISMATCH, p);
                        }
                    }
                }
                // R23c: CHECKPOINT action: nothing changes
            } else {
                // R19: Open check (PUT=2, DELETE=3, COMMIT=4, ABORT=5, SAVEPOINT=6, ROLLBACK=7, READ=9)
                int txnIdx = -1;
                for (int i = 0; i < openCount; i++) {
                    if (openTxns[i].id == txnId) {
                        txnIdx = i;
                        break;
                    }
                }
                if (txnIdx == -1) {
                    throw new WalException(Code.TXN_NOT_OPEN, p);
                }
                Txn t = openTxns[txnIdx];

                switch (type) {
                    case 2 -> {
                        // R19a: Write limit
                        if (t.writeCount == 8) {
                            throw new WalException(Code.TXN_TOO_LARGE, p);
                        }
                        // R21: PUT action
                        KeyEntry entry = currentState.getOrCreate(log, keyOff, k);
                        t.wKeys[t.writeCount] = entry;
                        t.wIsPut[t.writeCount] = true;
                        t.wValOff[t.writeCount] = valOff;
                        t.wValLen[t.writeCount] = v;
                        t.writeCount++;
                    }
                    case 3 -> {
                        // R19a: Write limit
                        if (t.writeCount == 8) {
                            throw new WalException(Code.TXN_TOO_LARGE, p);
                        }
                        // R21: DELETE action
                        KeyEntry entry = currentState.getOrCreate(log, keyOff, k);
                        t.wKeys[t.writeCount] = entry;
                        t.wIsPut[t.writeCount] = false;
                        t.writeCount++;
                    }
                    case 4 -> {
                        // R22: COMMIT action
                        boolean conflicts = false;
                        for (int i = 0; i < t.readCount; i++) {
                            if (t.readKeys[i].lastCommitOffset > t.beginOffset) {
                                conflicts = true;
                                break;
                            }
                        }
                        long uTxnId = Integer.toUnsignedLong(txnId);
                        if (!conflicts) {
                            if (t.writeCount > 0) {
                                // Preserve snapshot values for other still-open transactions before mutating
                                for (int w = 0; w < t.writeCount; w++) {
                                    KeyEntry entry = t.wKeys[w];
                                    for (int o = 0; o < openCount; o++) {
                                        if (o == txnIdx) continue;
                                        Txn other = openTxns[o];
                                        if (other.startSeq < currentSeq) {
                                            other.preserveIfAbsent(entry);
                                        }
                                    }
                                }
                                currentSeq++;
                                for (int w = 0; w < t.writeCount; w++) {
                                    KeyEntry entry = t.wKeys[w];
                                    entry.lastCommitOffset = p;
                                    if (t.wIsPut[w]) {
                                        entry.present = true;
                                        entry.valOff = t.wValOff[w];
                                        entry.valLen = t.wValLen[w];
                                    } else {
                                        entry.present = false;
                                    }
                                }
                            }
                            committed.add(uTxnId);
                        } else {
                            aborted.add(uTxnId);
                        }
                        // Close txn and return to pool
                        closeTxn(pool, openTxns, txnIdx, openCount);
                        openCount--;
                    }
                    case 5 -> {
                        // R23: ABORT action
                        aborted.add(Integer.toUnsignedLong(txnId));
                        closeTxn(pool, openTxns, txnIdx, openCount);
                        openCount--;
                    }
                    case 6 -> {
                        // R23a: SAVEPOINT action
                        int spIdx = -1;
                        for (int i = 0; i < t.spCount; i++) {
                            if (t.spNames[i] == name) {
                                spIdx = i;
                                break;
                            }
                        }
                        if (spIdx == -1) {
                            t.ensureSpCapacity();
                            t.spNames[t.spCount] = name;
                            t.spPos[t.spCount] = t.writeCount;
                            t.spOffset[t.spCount] = p;
                            t.spCount++;
                        } else {
                            t.spPos[spIdx] = t.writeCount;
                            t.spOffset[spIdx] = p;
                        }
                    }
                    case 7 -> {
                        // R19b: Savepoint check
                        int spIdx = -1;
                        for (int i = 0; i < t.spCount; i++) {
                            if (t.spNames[i] == name) {
                                spIdx = i;
                                break;
                            }
                        }
                        if (spIdx == -1) {
                            throw new WalException(Code.NO_SAVEPOINT, p);
                        }
                        // R23b: ROLLBACK action
                        int targetPos = t.spPos[spIdx];
                        int targetOff = t.spOffset[spIdx];
                        for (int i = targetPos; i < t.writeCount; i++) {
                            t.wKeys[i] = null;
                        }
                        t.writeCount = targetPos;
                        int newSpCount = 0;
                        for (int i = 0; i < t.spCount; i++) {
                            if (t.spOffset[i] <= targetOff) {
                                t.spNames[newSpCount] = t.spNames[i];
                                t.spPos[newSpCount] = t.spPos[i];
                                t.spOffset[newSpCount] = t.spOffset[i];
                                newSpCount++;
                            }
                        }
                        t.spCount = newSpCount;
                    }
                    case 9 -> {
                        // R19d: Read check
                        KeyEntry entry = currentState.getOrCreate(log, keyOff, k);
                        boolean viewPresent = false;
                        int viewValOff = 0;
                        int viewValLen = 0;
                        boolean foundInWriteList = false;

                        for (int w = t.writeCount - 1; w >= 0; w--) {
                            if (t.wKeys[w] == entry) {
                                foundInWriteList = true;
                                if (t.wIsPut[w]) {
                                    viewPresent = true;
                                    viewValOff = t.wValOff[w];
                                    viewValLen = t.wValLen[w];
                                } else {
                                    viewPresent = false;
                                }
                                break;
                            }
                        }

                        if (!foundInWriteList) {
                            int snapIdx = t.findSnap(entry);
                            if (snapIdx != -1) {
                                viewPresent = t.snapPresent[snapIdx];
                                viewValOff = t.snapValOff[snapIdx];
                                viewValLen = t.snapValLen[snapIdx];
                            } else {
                                viewPresent = entry.present;
                                viewValOff = entry.valOff;
                                viewValLen = entry.valLen;
                            }
                        }

                        if (f == 0) {
                            if (viewPresent) {
                                throw new WalException(Code.READ_MISMATCH, p);
                            }
                        } else {
                            if (!viewPresent || viewValLen != v || !Arrays.equals(log, viewValOff, viewValOff + viewValLen, log, valOff, valOff + v)) {
                                throw new WalException(Code.READ_MISMATCH, p);
                            }
                        }

                        // R23d: READ action
                        t.addReadKey(entry);
                    }
                }
            }

            p += 5 + N;
        }

        // Build final Result
        Map<String, String> state = new HashMap<>();
        KeyEntry[] table = currentState.table;
        for (int i = 0; i < table.length; i++) {
            KeyEntry e = table[i];
            if (e != null && e.present) {
                String kStr = new String(log, e.keyOff, e.keyLen, StandardCharsets.ISO_8859_1);
                String vStr = e.valLen == 0 ? "" : new String(log, e.valOff, e.valLen, StandardCharsets.ISO_8859_1);
                state.put(kStr, vStr);
            }
        }

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

        return new Result(state, committed, aborted, incomplete, validLength, tornTail);
    }

    private static void closeTxn(Txn[] pool, Txn[] openTxns, int idx, int openCount) {
        Txn closing = openTxns[idx];
        closing.clearRefs();
        int last = openCount - 1;
        openTxns[idx] = openTxns[last];
        openTxns[last] = null;
        pool[last] = closing;
    }

    private static int readU32AsInt(byte[] log, int offset) {
        return ((log[offset] & 0xFF) << 24)
                | ((log[offset + 1] & 0xFF) << 16)
                | ((log[offset + 2] & 0xFF) << 8)
                | (log[offset + 3] & 0xFF);
    }

    private static boolean checkFletcher16(byte[] log, int offset, int len) {
        int s1 = 0;
        int s2 = 0;
        int idx = offset;
        int end = offset + len;
        while (idx < end) {
            int chunkEnd = Math.min(end, idx + 4096);
            while (idx < chunkEnd) {
                s1 += log[idx++] & 0xFF;
                s2 += s1;
            }
            s1 %= 255;
            s2 %= 255;
        }
        int expectedS2 = log[end] & 0xFF;
        int expectedS1 = log[end + 1] & 0xFF;
        return s2 == expectedS2 && s1 == expectedS1;
    }

    private static final class KeyEntry {
        final int hash;
        final int keyOff;
        final int keyLen;
        boolean present;
        int valOff;
        int valLen;
        int lastCommitOffset = -1;

        KeyEntry(int hash, int keyOff, int keyLen) {
            this.hash = hash;
            this.keyOff = keyOff;
            this.keyLen = keyLen;
        }
    }

    private static final class KeyMap {
        KeyEntry[] table = new KeyEntry[1024];
        int size = 0;
        int mask = 1023;

        KeyEntry getOrCreate(byte[] log, int keyOff, int keyLen) {
            int h = hashKey(log, keyOff, keyLen);
            int idx = h & mask;
            while (true) {
                KeyEntry e = table[idx];
                if (e == null) {
                    if ((size + 1) * 4 >= table.length * 3) {
                        rehash();
                        idx = h & mask;
                        while (table[idx] != null) {
                            idx = (idx + 1) & mask;
                        }
                    }
                    e = new KeyEntry(h, keyOff, keyLen);
                    table[idx] = e;
                    size++;
                    return e;
                }
                if (e.hash == h && e.keyLen == keyLen && Arrays.equals(log, e.keyOff, e.keyOff + keyLen, log, keyOff, keyOff + keyLen)) {
                    return e;
                }
                idx = (idx + 1) & mask;
            }
        }

        private void rehash() {
            KeyEntry[] oldTable = table;
            int newCap = oldTable.length << 1;
            KeyEntry[] newTable = new KeyEntry[newCap];
            int newMask = newCap - 1;
            for (KeyEntry e : oldTable) {
                if (e != null) {
                    int idx = e.hash & newMask;
                    while (newTable[idx] != null) {
                        idx = (idx + 1) & newMask;
                    }
                    newTable[idx] = e;
                }
            }
            table = newTable;
            mask = newMask;
        }

        private static int hashKey(byte[] log, int off, int len) {
            int h = 0x811c9dc5;
            for (int i = 0; i < len; i++) {
                h ^= (log[off + i] & 0xFF);
                h *= 0x01000193;
            }
            h ^= (h >>> 16);
            return h;
        }
    }

    private static final class IntSet {
        private int[] table;
        private int size;
        private int mask;

        IntSet(int initialCapacity) {
            int cap = Integer.highestOneBit(initialCapacity - 1) << 1;
            if (cap < 16) cap = 16;
            table = new int[cap];
            mask = cap - 1;
        }

        boolean contains(int key) {
            int idx = mix(key) & mask;
            while (true) {
                int cur = table[idx];
                if (cur == 0) return false;
                if (cur == key) return true;
                idx = (idx + 1) & mask;
            }
        }

        void add(int key) {
            if ((size + 1) * 4 >= table.length * 3) {
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
                if (cur == key) return;
                idx = (idx + 1) & mask;
            }
        }

        private void rehash() {
            int[] old = table;
            int newCap = old.length << 1;
            int[] next = new int[newCap];
            int newMask = newCap - 1;
            for (int k : old) {
                if (k != 0) {
                    int idx = mix(k) & newMask;
                    while (next[idx] != 0) {
                        idx = (idx + 1) & newMask;
                    }
                    next[idx] = k;
                }
            }
            table = next;
            mask = newMask;
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

    private static final class Txn {
        int id;
        int beginOffset;
        long startSeq;

        // Write list (max 8)
        final KeyEntry[] wKeys = new KeyEntry[8];
        final boolean[] wIsPut = new boolean[8];
        final int[] wValOff = new int[8];
        final int[] wValLen = new int[8];
        int writeCount;

        // Savepoints (at most 94 distinct valid ASCII names: 0x21..0x7E)
        int[] spNames = new int[16];
        int[] spPos = new int[16];
        int[] spOffset = new int[16];
        int spCount;

        // Read set
        KeyEntry[] readKeys = new KeyEntry[16];
        int readCount;
        KeyEntry[] readSetTable = null;
        int readSetMask = 0;

        // Copy-on-write snapshot overrides for keys modified by other txns after BEGIN
        KeyEntry[] snapKeys = new KeyEntry[16];
        boolean[] snapPresent = new boolean[16];
        int[] snapValOff = new int[16];
        int[] snapValLen = new int[16];
        int snapCount;
        int[] snapHashIdx = null;
        int snapHashMask = 0;

        void reset(int id, int beginOffset, long startSeq) {
            this.id = id;
            this.beginOffset = beginOffset;
            this.startSeq = startSeq;
            this.writeCount = 0;
            this.spCount = 0;
            this.readCount = 0;
            this.snapCount = 0;
            if (readSetTable != null) {
                Arrays.fill(readSetTable, null);
            }
            if (snapHashIdx != null) {
                Arrays.fill(snapHashIdx, -1);
            }
        }

        void clearRefs() {
            Arrays.fill(wKeys, 0, writeCount, null);
            Arrays.fill(readKeys, 0, readCount, null);
            Arrays.fill(snapKeys, 0, snapCount, null);
            if (readSetTable != null) {
                Arrays.fill(readSetTable, null);
            }
        }

        void ensureSpCapacity() {
            if (spCount == spNames.length) {
                int newCap = spNames.length << 1;
                spNames = Arrays.copyOf(spNames, newCap);
                spPos = Arrays.copyOf(spPos, newCap);
                spOffset = Arrays.copyOf(spOffset, newCap);
            }
        }

        void addReadKey(KeyEntry key) {
            if (readSetTable == null) {
                for (int i = 0; i < readCount; i++) {
                    if (readKeys[i] == key) return;
                }
                if (readCount == readKeys.length) {
                    readKeys = Arrays.copyOf(readKeys, readKeys.length << 1);
                }
                readKeys[readCount++] = key;
                if (readCount > 16) {
                    initReadHash();
                }
            } else {
                if ((readCount + 1) * 4 >= readSetTable.length * 3) {
                    rehashReadSet();
                }
                int idx = key.hash & readSetMask;
                while (true) {
                    KeyEntry cur = readSetTable[idx];
                    if (cur == null) {
                        readSetTable[idx] = key;
                        if (readCount == readKeys.length) {
                            readKeys = Arrays.copyOf(readKeys, readKeys.length << 1);
                        }
                        readKeys[readCount++] = key;
                        return;
                    }
                    if (cur == key) return;
                    idx = (idx + 1) & readSetMask;
                }
            }
        }

        private void initReadHash() {
            int cap = 64;
            readSetTable = new KeyEntry[cap];
            readSetMask = cap - 1;
            for (int i = 0; i < readCount; i++) {
                KeyEntry k = readKeys[i];
                int idx = k.hash & readSetMask;
                while (readSetTable[idx] != null) {
                    idx = (idx + 1) & readSetMask;
                }
                readSetTable[idx] = k;
            }
        }

        private void rehashReadSet() {
            int newCap = readSetTable.length << 1;
            KeyEntry[] next = new KeyEntry[newCap];
            int newMask = newCap - 1;
            for (int i = 0; i < readCount; i++) {
                KeyEntry k = readKeys[i];
                int idx = k.hash & newMask;
                while (next[idx] != null) {
                    idx = (idx + 1) & newMask;
                }
                next[idx] = k;
            }
            readSetTable = next;
            readSetMask = newMask;
        }

        int findSnap(KeyEntry key) {
            if (snapHashIdx == null) {
                for (int i = 0; i < snapCount; i++) {
                    if (snapKeys[i] == key) return i;
                }
                return -1;
            } else {
                int idx = key.hash & snapHashMask;
                while (true) {
                    int pos = snapHashIdx[idx];
                    if (pos == -1) return -1;
                    if (snapKeys[pos] == key) return pos;
                    idx = (idx + 1) & snapHashMask;
                }
            }
        }

        void preserveIfAbsent(KeyEntry key) {
            if (findSnap(key) != -1) return;
            if (snapCount == snapKeys.length) {
                int newCap = snapKeys.length << 1;
                snapKeys = Arrays.copyOf(snapKeys, newCap);
                snapPresent = Arrays.copyOf(snapPresent, newCap);
                snapValOff = Arrays.copyOf(snapValOff, newCap);
                snapValLen = Arrays.copyOf(snapValLen, newCap);
            }
            int pos = snapCount++;
            snapKeys[pos] = key;
            snapPresent[pos] = key.present;
            snapValOff[pos] = key.valOff;
            snapValLen[pos] = key.valLen;

            if (snapHashIdx == null) {
                if (snapCount > 16) {
                    initSnapHash();
                }
            } else {
                if (snapCount * 4 >= snapHashIdx.length * 3) {
                    rehashSnap();
                } else {
                    int idx = key.hash & snapHashMask;
                    while (snapHashIdx[idx] != -1) {
                        idx = (idx + 1) & snapHashMask;
                    }
                    snapHashIdx[idx] = pos;
                }
            }
        }

        private void initSnapHash() {
            int cap = 64;
            snapHashIdx = new int[cap];
            Arrays.fill(snapHashIdx, -1);
            snapHashMask = cap - 1;
            for (int i = 0; i < snapCount; i++) {
                int idx = snapKeys[i].hash & snapHashMask;
                while (snapHashIdx[idx] != -1) {
                    idx = (idx + 1) & snapHashMask;
                }
                snapHashIdx[idx] = i;
            }
        }

        private void rehashSnap() {
            int newCap = snapHashIdx.length << 1;
            int[] next = new int[newCap];
            Arrays.fill(next, -1);
            int newMask = newCap - 1;
            for (int i = 0; i < snapCount; i++) {
                int idx = snapKeys[i].hash & newMask;
                while (next[idx] != -1) {
                    idx = (idx + 1) & newMask;
                }
                next[idx] = i;
            }
            snapHashIdx = next;
            snapHashMask = newMask;
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
