import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final int NUM_SAVEPOINT_NAMES = NAME_BYTE_MAX - NAME_BYTE_MIN + 1;

    private static final int TYPE_BEGIN = 1;
    private static final int TYPE_PUT = 2;
    private static final int TYPE_DELETE = 3;
    private static final int TYPE_COMMIT = 4;
    private static final int TYPE_ABORT = 5;
    private static final int TYPE_SAVEPOINT = 6;
    private static final int TYPE_ROLLBACK = 7;
    private static final int TYPE_CHECKPOINT = 8;
    private static final int TYPE_READ = 9;
    private static final int TYPE_RANGE = 10;

    private WalRecovery() {}

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        // R1
        if (log == null) {
            throw new NullPointerException();
        }
        final int len = log.length;

        // R2
        if (len == 0) {
            return emptyResult(0, false);
        }

        // R3
        if (len < HEADER_SIZE) {
            for (int i = 0; i < len; i++) {
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

        // Phase 1: frames (R5 - R12)
        int validLength;
        boolean tornTail;
        int p = HEADER_SIZE;
        while (true) {
            // R5
            if (p == len) {
                validLength = len;
                tornTail = false;
                break;
            }
            // R6
            if (len - p <= 2) {
                validLength = p;
                tornTail = true;
                break;
            }
            // R7
            int type = log[p] & 0xFF;
            if (type < TYPE_BEGIN || type > TYPE_RANGE) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }
            // R8
            int n = u16(log, p + 1);
            if (n > MAX_PAYLOAD) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }
            // R9
            if (5 + n > len - p) {
                validLength = p;
                tornTail = true;
                break;
            }
            // R10: Fletcher-16 over 3 + n bytes at p .. p + 2 + n
            int s1 = 0;
            int s2 = 0;
            int end = p + 3 + n;
            for (int i = p; i < end; i++) {
                s1 += log[i] & 0xFF;
                s2 += s1;
            }
            s1 %= 255;
            s2 %= 255;
            int storedS2 = log[end] & 0xFF;
            int storedS1 = log[end + 1] & 0xFF;
            if (s2 != storedS2 || s1 != storedS1) {
                if (p + 5 + n == len) {
                    validLength = p;
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }
            // R11
            p += 5 + n;
        }

        if (validLength == HEADER_SIZE) {
            return emptyResult(validLength, tornTail);
        }

        // Phase 2: replay (R13 - R27)
        IntHashSet seenTxns = new IntHashSet(Math.min((validLength - HEADER_SIZE) / 9, 2_000_000));
        Txn[] openTxns = new Txn[MAX_OPEN_TXNS];
        int openCount = 0;
        Txn[] txnPool = new Txn[MAX_OPEN_TXNS];
        int poolCount = MAX_OPEN_TXNS;
        for (int i = 0; i < MAX_OPEN_TXNS; i++) {
            txnPool[i] = new Txn();
        }

        StateNode stateRoot = null;
        LWNode lwRoot = null;
        int globalCommitSeq = 0;

        ArrayList<Long> committed = new ArrayList<>();
        ArrayList<Long> aborted = new ArrayList<>();

        p = HEADER_SIZE;
        while (p < validLength) {
            int type = log[p] & 0xFF;
            int n = u16(log, p + 1);
            int pay = p + 3;

            int k = 0;
            int v = 0;
            int f = 0;
            int k1 = 0;
            int k2 = 0;
            int c = 0;

            // R13: Payload layout
            switch (type) {
                case TYPE_BEGIN, TYPE_COMMIT, TYPE_ABORT -> {
                    if (n != 4) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                }
                case TYPE_PUT -> {
                    if (n < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                    k = log[pay + 4] & 0xFF;
                    if (n < 7 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                    v = u16(log, pay + 5 + k);
                    if (n != 7 + k + v) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                }
                case TYPE_DELETE -> {
                    if (n < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                    k = log[pay + 4] & 0xFF;
                    if (n != 5 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                }
                case TYPE_SAVEPOINT, TYPE_ROLLBACK -> {
                    if (n != 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                }
                case TYPE_CHECKPOINT -> {
                    if (n < 1) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                    c = log[pay] & 0xFF;
                    if (n != 1 + 4 * c) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                }
                case TYPE_READ -> {
                    if (n < 6) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                    k = log[pay + 4] & 0xFF;
                    if (n < 6 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                    f = log[pay + 5 + k] & 0xFF;
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
                }
                case TYPE_RANGE -> {
                    if (n < 6) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                    k1 = log[pay + 4] & 0xFF;
                    if (n < 6 + k1) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                    k2 = log[pay + 5 + k1] & 0xFF;
                    if (n != 10 + k1 + k2) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, p);
                    }
                }
            }

            // R14: Txn ids
            long txn = 0;
            if (type == TYPE_CHECKPOINT) {
                for (int i = 0; i < c; i++) {
                    if (u32(log, pay + 1 + 4 * i) == 0) {
                        throw new WalException(Code.BAD_TXN_ID, p);
                    }
                }
            } else {
                txn = u32(log, pay);
                if (txn == 0) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
            }

            // R15: Key
            if (type == TYPE_PUT || type == TYPE_DELETE || type == TYPE_READ) {
                validateKey(log, pay + 5, k, p);
            } else if (type == TYPE_RANGE) {
                validateKey(log, pay + 5, k1, p);
                validateKey(log, pay + 6 + k1, k2, p);
            }

            // R16: Value
            if (type == TYPE_PUT) {
                validateValue(log, pay + 7 + k, v, p);
            } else if (type == TYPE_READ && f == 1) {
                validateValue(log, pay + 8 + k, v, p);
            }

            // R16a: Name
            int spName = 0;
            if (type == TYPE_SAVEPOINT || type == TYPE_ROLLBACK) {
                spName = log[pay + 4] & 0xFF;
                if (spName < NAME_BYTE_MIN || spName > NAME_BYTE_MAX) {
                    throw new WalException(Code.BAD_NAME, p);
                }
            }

            // Transaction checks and actions (R17 - R23e)
            if (type == TYPE_BEGIN) {
                // R17
                int rawTxn = (int) txn;
                if (seenTxns.contains(rawTxn)) {
                    throw new WalException(Code.DUPLICATE_TXN, p);
                }
                // R18
                if (openCount == MAX_OPEN_TXNS) {
                    throw new WalException(Code.TOO_MANY_OPEN, p);
                }
                // R20
                seenTxns.add(rawTxn);
                Txn t = txnPool[--poolCount];
                t.init(txn, stateRoot, globalCommitSeq);
                int ins = openCount;
                while (ins > 0 && openTxns[ins - 1].id > txn) {
                    openTxns[ins] = openTxns[ins - 1];
                    ins--;
                }
                openTxns[ins] = t;
                openCount++;
            } else if (type == TYPE_CHECKPOINT) {
                // R19c
                if (c != openCount) {
                    throw new WalException(Code.CHECKPOINT_MISMATCH, p);
                }
                for (int i = 0; i < c; i++) {
                    if (u32(log, pay + 1 + 4 * i) != openTxns[i].id) {
                        throw new WalException(Code.CHECKPOINT_MISMATCH, p);
                    }
                }
                // R23c: no-op
            } else {
                // R19
                int openIdx = -1;
                for (int i = 0; i < openCount; i++) {
                    if (openTxns[i].id == txn) {
                        openIdx = i;
                        break;
                    }
                }
                if (openIdx < 0) {
                    throw new WalException(Code.TXN_NOT_OPEN, p);
                }
                Txn t = openTxns[openIdx];

                switch (type) {
                    case TYPE_PUT -> {
                        // R19a
                        if (t.writeCount == MAX_WRITES) {
                            throw new WalException(Code.TXN_TOO_LARGE, p);
                        }
                        // R21
                        String keyStr = new String(log, pay + 5, k, StandardCharsets.ISO_8859_1);
                        String valStr = v == 0 ? "" : new String(log, pay + 7 + k, v, StandardCharsets.ISO_8859_1);
                        t.writeKeys[t.writeCount] = keyStr;
                        t.writeValues[t.writeCount] = valStr;
                        t.writeCount++;
                    }
                    case TYPE_DELETE -> {
                        // R19a
                        if (t.writeCount == MAX_WRITES) {
                            throw new WalException(Code.TXN_TOO_LARGE, p);
                        }
                        // R21
                        String keyStr = new String(log, pay + 5, k, StandardCharsets.ISO_8859_1);
                        t.writeKeys[t.writeCount] = keyStr;
                        t.writeValues[t.writeCount] = null;
                        t.writeCount++;
                    }
                    case TYPE_COMMIT -> {
                        // R22
                        for (int i = openIdx; i < openCount - 1; i++) {
                            openTxns[i] = openTxns[i + 1];
                        }
                        openTxns[--openCount] = null;

                        boolean conflict = false;
                        int minSeq = t.beginCommitSeq;
                        if (globalCommitSeq > minSeq) {
                            ArrayList<String> rks = t.readKeys;
                            for (int i = 0, sz = rks.size(); i < sz; i++) {
                                if (lwGetSeq(lwRoot, rks.get(i), minSeq) > minSeq) {
                                    conflict = true;
                                    break;
                                }
                            }
                            if (!conflict) {
                                ArrayList<String> los = t.rangeLos;
                                ArrayList<String> his = t.rangeHis;
                                for (int i = 0, sz = los.size(); i < sz; i++) {
                                    if (lwHasCommitAfter(lwRoot, los.get(i), his.get(i), minSeq, false, false)) {
                                        conflict = true;
                                        break;
                                    }
                                }
                            }
                        }

                        if (!conflict) {
                            globalCommitSeq++;
                            for (int i = 0; i < t.writeCount; i++) {
                                String wk = t.writeKeys[i];
                                String wv = t.writeValues[i];
                                if (wv != null) {
                                    stateRoot = statePut(stateRoot, wk, wv);
                                } else {
                                    stateRoot = stateDelete(stateRoot, wk);
                                }
                                lwRoot = lwPut(lwRoot, wk, globalCommitSeq);
                            }
                            committed.add(t.id);
                        } else {
                            aborted.add(t.id);
                        }

                        t.reset();
                        txnPool[poolCount++] = t;
                    }
                    case TYPE_ABORT -> {
                        // R23
                        for (int i = openIdx; i < openCount - 1; i++) {
                            openTxns[i] = openTxns[i + 1];
                        }
                        openTxns[--openCount] = null;
                        aborted.add(t.id);
                        t.reset();
                        txnPool[poolCount++] = t;
                    }
                    case TYPE_SAVEPOINT -> {
                        // R23a
                        t.setSavepoint(spName - NAME_BYTE_MIN, p);
                    }
                    case TYPE_ROLLBACK -> {
                        // R19b
                        int spIdx = spName - NAME_BYTE_MIN;
                        if (t.spOffset[spIdx] < 0) {
                            throw new WalException(Code.NO_SAVEPOINT, p);
                        }
                        // R23b
                        t.rollbackTo(spIdx);
                    }
                    case TYPE_READ -> {
                        // R19d
                        String keyStr = new String(log, pay + 5, k, StandardCharsets.ISO_8859_1);
                        boolean foundInWrites = false;
                        String viewVal = null;
                        for (int i = t.writeCount - 1; i >= 0; i--) {
                            if (t.writeKeys[i].equals(keyStr)) {
                                foundInWrites = true;
                                viewVal = t.writeValues[i];
                                keyStr = t.writeKeys[i];
                                break;
                            }
                        }
                        if (!foundInWrites) {
                            StateNode sn = stateGetNode(t.snapshotRoot, keyStr);
                            if (sn != null) {
                                viewVal = sn.value;
                                keyStr = sn.key;
                            }
                        }
                        if (f == 0) {
                            if (viewVal != null) {
                                throw new WalException(Code.READ_MISMATCH, p);
                            }
                        } else {
                            if (viewVal == null || viewVal.length() != v) {
                                throw new WalException(Code.READ_MISMATCH, p);
                            }
                            int valOff = pay + 8 + k;
                            for (int i = 0; i < v; i++) {
                                if (viewVal.charAt(i) != (log[valOff + i] & 0xFF)) {
                                    throw new WalException(Code.READ_MISMATCH, p);
                                }
                            }
                        }
                        // R23d
                        t.addReadKey(keyStr);
                    }
                    case TYPE_RANGE -> {
                        // R19e
                        String lo = new String(log, pay + 5, k1, StandardCharsets.ISO_8859_1);
                        String hi = new String(log, pay + 6 + k1, k2, StandardCharsets.ISO_8859_1);
                        long expectedCount = u32(log, pay + 6 + k1 + k2);

                        int actualCount;
                        if (lo.compareTo(hi) > 0) {
                            actualCount = 0;
                        } else {
                            actualCount = countLessOrEqual(t.snapshotRoot, hi) - countStrictlyLess(t.snapshotRoot, lo);
                            for (int i = t.writeCount - 1; i >= 0; i--) {
                                String wk = t.writeKeys[i];
                                boolean seenLater = false;
                                for (int j = i + 1; j < t.writeCount; j++) {
                                    if (t.writeKeys[j].equals(wk)) {
                                        seenLater = true;
                                        break;
                                    }
                                }
                                if (seenLater) {
                                    continue;
                                }
                                if (lo.compareTo(wk) <= 0 && wk.compareTo(hi) <= 0) {
                                    boolean inSnap = stateGetNode(t.snapshotRoot, wk) != null;
                                    boolean inWrites = t.writeValues[i] != null;
                                    if (inWrites && !inSnap) {
                                        actualCount++;
                                    } else if (!inWrites && inSnap) {
                                        actualCount--;
                                    }
                                }
                            }
                        }
                        if ((long) actualCount != expectedCount) {
                            throw new WalException(Code.RANGE_MISMATCH, p);
                        }
                        // R23e
                        t.addReadRange(lo, hi);
                    }
                }
            }

            p += 5 + n;
        }

        // Build Result (R24 - R27)
        Map<String, String> stateMap = HashMap.newHashMap(size(stateRoot));
        fillMap(stateRoot, stateMap);

        ArrayList<Long> incomplete = new ArrayList<>(openCount);
        for (int i = 0; i < openCount; i++) {
            incomplete.add(openTxns[i].id);
        }

        return new Result(stateMap, committed, aborted, incomplete, validLength, tornTail);
    }

    private static Result emptyResult(int validLength, boolean tornTail) {
        return new Result(new HashMap<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), validLength, tornTail);
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32(byte[] b, int off) {
        return (((long) (b[off] & 0xFF)) << 24)
             | (((long) (b[off + 1] & 0xFF)) << 16)
             | (((long) (b[off + 2] & 0xFF)) << 8)
             | ((long) (b[off + 3] & 0xFF));
    }

    private static void validateKey(byte[] log, int off, int len, int recordOffset) throws WalException {
        if (len < MIN_KEY_LEN || len > MAX_KEY_LEN) {
            throw new WalException(Code.BAD_KEY, recordOffset);
        }
        for (int i = 0; i < len; i++) {
            int b = log[off + i] & 0xFF;
            if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                throw new WalException(Code.BAD_KEY, recordOffset);
            }
        }
    }

    private static void validateValue(byte[] log, int off, int len, int recordOffset) throws WalException {
        for (int i = 0; i < len; i++) {
            int b = log[off + i] & 0xFF;
            if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                throw new WalException(Code.BAD_VALUE, recordOffset);
            }
        }
    }

    // Persistent Order-Statistic AVL Tree for state and snapshots
    private static final class StateNode {
        final String key;
        final String value;
        final StateNode left;
        final StateNode right;
        final int height;
        final int size;

        StateNode(String key, String value, StateNode left, StateNode right) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            int lh = left == null ? 0 : left.height;
            int rh = right == null ? 0 : right.height;
            this.height = 1 + (lh > rh ? lh : rh);
            int ls = left == null ? 0 : left.size;
            int rs = right == null ? 0 : right.size;
            this.size = 1 + ls + rs;
        }
    }

    private static int height(StateNode n) {
        return n == null ? 0 : n.height;
    }

    private static int size(StateNode n) {
        return n == null ? 0 : n.size;
    }

    private static StateNode rotateRight(StateNode y) {
        StateNode x = y.left;
        StateNode newY = new StateNode(y.key, y.value, x.right, y.right);
        return new StateNode(x.key, x.value, x.left, newY);
    }

    private static StateNode rotateLeft(StateNode x) {
        StateNode y = x.right;
        StateNode newX = new StateNode(x.key, x.value, x.left, y.left);
        return new StateNode(y.key, y.value, newX, y.right);
    }

    private static StateNode balance(String key, String value, StateNode left, StateNode right) {
        int bf = height(left) - height(right);
        if (bf > 1) {
            if (height(left.left) < height(left.right)) {
                left = rotateLeft(left);
            }
            return rotateRight(new StateNode(key, value, left, right));
        }
        if (bf < -1) {
            if (height(right.right) < height(right.left)) {
                right = rotateRight(right);
            }
            return rotateLeft(new StateNode(key, value, left, right));
        }
        return new StateNode(key, value, left, right);
    }

    private static StateNode statePut(StateNode node, String key, String value) {
        if (node == null) {
            return new StateNode(key, value, null, null);
        }
        int cmp = key.compareTo(node.key);
        if (cmp < 0) {
            StateNode nl = statePut(node.left, key, value);
            if (nl == node.left) {
                return node;
            }
            return balance(node.key, node.value, nl, node.right);
        } else if (cmp > 0) {
            StateNode nr = statePut(node.right, key, value);
            if (nr == node.right) {
                return node;
            }
            return balance(node.key, node.value, node.left, nr);
        } else {
            if (value.equals(node.value)) {
                return node;
            }
            return new StateNode(key, value, node.left, node.right);
        }
    }

    private static StateNode stateDelete(StateNode node, String key) {
        if (node == null) {
            return null;
        }
        int cmp = key.compareTo(node.key);
        if (cmp < 0) {
            StateNode nl = stateDelete(node.left, key);
            if (nl == node.left) {
                return node;
            }
            return balance(node.key, node.value, nl, node.right);
        } else if (cmp > 0) {
            StateNode nr = stateDelete(node.right, key);
            if (nr == node.right) {
                return node;
            }
            return balance(node.key, node.value, node.left, nr);
        } else {
            if (node.left == null) {
                return node.right;
            }
            if (node.right == null) {
                return node.left;
            }
            StateNode min = node.right;
            while (min.left != null) {
                min = min.left;
            }
            StateNode nr = deleteMin(node.right);
            return balance(min.key, min.value, node.left, nr);
        }
    }

    private static StateNode deleteMin(StateNode node) {
        if (node.left == null) {
            return node.right;
        }
        return balance(node.key, node.value, deleteMin(node.left), node.right);
    }

    private static StateNode stateGetNode(StateNode node, String key) {
        while (node != null) {
            int cmp = key.compareTo(node.key);
            if (cmp < 0) {
                node = node.left;
            } else if (cmp > 0) {
                node = node.right;
            } else {
                return node;
            }
        }
        return null;
    }

    private static int countStrictlyLess(StateNode node, String bound) {
        int count = 0;
        while (node != null) {
            if (node.key.compareTo(bound) < 0) {
                count += 1 + size(node.left);
                node = node.right;
            } else {
                node = node.left;
            }
        }
        return count;
    }

    private static int countLessOrEqual(StateNode node, String bound) {
        int count = 0;
        while (node != null) {
            if (node.key.compareTo(bound) <= 0) {
                count += 1 + size(node.left);
                node = node.right;
            } else {
                node = node.left;
            }
        }
        return count;
    }

    private static void fillMap(StateNode node, Map<String, String> map) {
        if (node == null) {
            return;
        }
        fillMap(node.left, map);
        map.put(node.key, node.value);
        fillMap(node.right, map);
    }

    // Mutable Augmented AVL Tree tracking last commit sequence per written key
    private static final class LWNode {
        final String key;
        int seq;
        int maxSeq;
        int height;
        LWNode left;
        LWNode right;

        LWNode(String key, int seq) {
            this.key = key;
            this.seq = seq;
            this.maxSeq = seq;
            this.height = 1;
        }
    }

    private static int lwHeight(LWNode n) {
        return n == null ? 0 : n.height;
    }

    private static void lwUpdate(LWNode n) {
        int lh = 0;
        int rh = 0;
        int m = n.seq;
        LWNode l = n.left;
        if (l != null) {
            lh = l.height;
            if (l.maxSeq > m) {
                m = l.maxSeq;
            }
        }
        LWNode r = n.right;
        if (r != null) {
            rh = r.height;
            if (r.maxSeq > m) {
                m = r.maxSeq;
            }
        }
        n.height = 1 + (lh > rh ? lh : rh);
        n.maxSeq = m;
    }

    private static LWNode lwRotateRight(LWNode y) {
        LWNode x = y.left;
        y.left = x.right;
        x.right = y;
        lwUpdate(y);
        lwUpdate(x);
        return x;
    }

    private static LWNode lwRotateLeft(LWNode x) {
        LWNode y = x.right;
        x.right = y.left;
        y.left = x;
        lwUpdate(x);
        lwUpdate(y);
        return y;
    }

    private static LWNode lwBalance(LWNode n) {
        lwUpdate(n);
        int bf = lwHeight(n.left) - lwHeight(n.right);
        if (bf > 1) {
            if (lwHeight(n.left.left) < lwHeight(n.left.right)) {
                n.left = lwRotateLeft(n.left);
            }
            return lwRotateRight(n);
        }
        if (bf < -1) {
            if (lwHeight(n.right.right) < lwHeight(n.right.left)) {
                n.right = lwRotateRight(n.right);
            }
            return lwRotateLeft(n);
        }
        return n;
    }

    private static LWNode lwPut(LWNode node, String key, int seq) {
        if (node == null) {
            return new LWNode(key, seq);
        }
        int cmp = key.compareTo(node.key);
        if (cmp < 0) {
            node.left = lwPut(node.left, key, seq);
            return lwBalance(node);
        } else if (cmp > 0) {
            node.right = lwPut(node.right, key, seq);
            return lwBalance(node);
        } else {
            node.seq = seq;
            lwUpdate(node);
            return node;
        }
    }

    private static int lwGetSeq(LWNode node, String key, int minSeq) {
        while (node != null && node.maxSeq > minSeq) {
            int cmp = key.compareTo(node.key);
            if (cmp < 0) {
                node = node.left;
            } else if (cmp > 0) {
                node = node.right;
            } else {
                return node.seq;
            }
        }
        return 0;
    }

    private static boolean lwHasCommitAfter(LWNode node, String lo, String hi, int minSeq,
                                            boolean loUnbounded, boolean hiUnbounded) {
        if (node == null || node.maxSeq <= minSeq) {
            return false;
        }
        if (loUnbounded && hiUnbounded) {
            return true;
        }
        int cmpLo = loUnbounded ? -1 : lo.compareTo(node.key);
        int cmpHi = hiUnbounded ? 1 : hi.compareTo(node.key);
        if (cmpLo <= 0 && cmpHi >= 0 && node.seq > minSeq) {
            return true;
        }
        if (cmpLo < 0) {
            if (lwHasCommitAfter(node.left, lo, hi, minSeq, loUnbounded, hiUnbounded || cmpHi >= 0)) {
                return true;
            }
        }
        if (cmpHi > 0) {
            if (lwHasCommitAfter(node.right, lo, hi, minSeq, loUnbounded || cmpLo <= 0, hiUnbounded)) {
                return true;
            }
        }
        return false;
    }

    // Open-transaction state
    private static final class Txn {
        long id;
        StateNode snapshotRoot;
        int beginCommitSeq;

        final String[] writeKeys = new String[MAX_WRITES];
        final String[] writeValues = new String[MAX_WRITES];
        int writeCount;

        final int[] spPos = new int[NUM_SAVEPOINT_NAMES];
        final int[] spOffset = new int[NUM_SAVEPOINT_NAMES];
        final int[] activeNames = new int[NUM_SAVEPOINT_NAMES];
        int activeSpCount;

        ArrayList<String> readKeys = new ArrayList<>();
        ArrayList<String> rangeLos = new ArrayList<>();
        ArrayList<String> rangeHis = new ArrayList<>();

        Txn() {
            Arrays.fill(spOffset, -1);
        }

        void init(long id, StateNode snapshotRoot, int beginCommitSeq) {
            this.id = id;
            this.snapshotRoot = snapshotRoot;
            this.beginCommitSeq = beginCommitSeq;
            this.writeCount = 0;
            this.activeSpCount = 0;
        }

        void reset() {
            this.snapshotRoot = null;
            for (int i = 0; i < writeCount; i++) {
                writeKeys[i] = null;
                writeValues[i] = null;
            }
            writeCount = 0;
            for (int i = 0; i < activeSpCount; i++) {
                spOffset[activeNames[i]] = -1;
            }
            activeSpCount = 0;
            if (readKeys.size() > 1024) {
                readKeys = new ArrayList<>();
            } else {
                readKeys.clear();
            }
            if (rangeLos.size() > 1024) {
                rangeLos = new ArrayList<>();
                rangeHis = new ArrayList<>();
            } else {
                rangeLos.clear();
                rangeHis.clear();
            }
        }

        void setSavepoint(int spIdx, int offset) {
            if (spOffset[spIdx] < 0) {
                activeNames[activeSpCount++] = spIdx;
            }
            spPos[spIdx] = writeCount;
            spOffset[spIdx] = offset;
        }

        void rollbackTo(int spIdx) {
            int targetPos = spPos[spIdx];
            for (int i = targetPos; i < writeCount; i++) {
                writeKeys[i] = null;
                writeValues[i] = null;
            }
            writeCount = targetPos;
            int targetOffset = spOffset[spIdx];
            int newActive = 0;
            for (int i = 0; i < activeSpCount; i++) {
                int nm = activeNames[i];
                if (spOffset[nm] > targetOffset) {
                    spOffset[nm] = -1;
                } else {
                    activeNames[newActive++] = nm;
                }
            }
            activeSpCount = newActive;
        }

        void addReadKey(String key) {
            int sz = readKeys.size();
            if (sz == 0 || !readKeys.get(sz - 1).equals(key)) {
                readKeys.add(key);
            }
        }

        void addReadRange(String lo, String hi) {
            if (lo.compareTo(hi) <= 0) {
                int sz = rangeLos.size();
                if (sz == 0 || !rangeLos.get(sz - 1).equals(lo) || !rangeHis.get(sz - 1).equals(hi)) {
                    rangeLos.add(lo);
                    rangeHis.add(hi);
                }
            }
        }
    }

    // Primitive open-addressing set of non-zero 32-bit txn ids
    private static final class IntHashSet {
        private int[] table;
        private int mask;
        private int size;
        private int threshold;

        IntHashSet(int expectedElements) {
            int cap = 16;
            int needed = Math.max(16, expectedElements * 2);
            while (cap < needed && cap < (1 << 22)) {
                cap <<= 1;
            }
            table = new int[cap];
            mask = cap - 1;
            threshold = (cap * 3) >>> 2;
        }

        boolean contains(int key) {
            int[] tab = table;
            int m = mask;
            int idx = mix(key) & m;
            while (true) {
                int cur = tab[idx];
                if (cur == 0) {
                    return false;
                }
                if (cur == key) {
                    return true;
                }
                idx = (idx + 1) & m;
            }
        }

        void add(int key) {
            if (size >= threshold) {
                rehash();
            }
            int[] tab = table;
            int m = mask;
            int idx = mix(key) & m;
            while (true) {
                int cur = tab[idx];
                if (cur == 0) {
                    tab[idx] = key;
                    size++;
                    return;
                }
                if (cur == key) {
                    return;
                }
                idx = (idx + 1) & m;
            }
        }

        private void rehash() {
            int[] oldTab = table;
            int newCap = oldTab.length << 1;
            int[] newTab = new int[newCap];
            int m = newCap - 1;
            for (int k : oldTab) {
                if (k != 0) {
                    int idx = mix(k) & m;
                    while (newTab[idx] != 0) {
                        idx = (idx + 1) & m;
                    }
                    newTab[idx] = k;
                }
            }
            table = newTab;
            mask = m;
            threshold = (newCap * 3) >>> 2;
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
