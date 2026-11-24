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

    private WalRecovery() {}

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        // R1
        Objects.requireNonNull(log, "log must not be null");

        int len = log.length;

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
        for (int i = 0; i < HEADER_SIZE; i++) {
            if (log[i] != MAGIC[i]) {
                throw new WalException(Code.BAD_MAGIC, 0);
            }
        }

        // Phase 1: frames
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
            int remaining = len - p;
            if (remaining == 1 || remaining == 2) {
                validLength = p;
                tornTail = true;
                break;
            }

            // R7
            int type = u8(log, p);
            if (type < TYPE_BEGIN || type > TYPE_CHECKPOINT) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }

            // R8
            int n = u16(log, p + 1);
            if (n > MAX_PAYLOAD) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }

            // R9
            long recordEnd = (long) p + 5L + n;
            if (recordEnd > len) {
                validLength = p;
                tornTail = true;
                break;
            }

            // R10
            int endOffset = (int) recordEnd;
            int s1 = 0;
            int s2 = 0;
            int checksumEnd = p + 3 + n;
            for (int i = p; i < checksumEnd; i++) {
                s1 = (s1 + (log[i] & 0xFF)) % 255;
                s2 = (s2 + s1) % 255;
            }
            int storedS2 = u8(log, p + 3 + n);
            int storedS1 = u8(log, p + 4 + n);
            if (storedS2 != s2 || storedS1 != s1) {
                if (endOffset == len) {
                    validLength = p;
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }

            // R11
            intactRecords.add(new IntactRecord(p, type, n));
            p = endOffset;
        }

        // Phase 2: replay
        Map<String, String> state = new LinkedHashMap<>();
        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();
        Set<Long> seenTxns = new HashSet<>();
        TreeMap<Long, TxnState> openTxns = new TreeMap<>();

        for (IntactRecord rec : intactRecords) {
            replayRecord(log, rec, state, committed, aborted, seenTxns, openTxns);
        }

        List<Long> incomplete = List.copyOf(openTxns.keySet());
        return new Result(
                Collections.unmodifiableMap(state),
                List.copyOf(committed),
                List.copyOf(aborted),
                incomplete,
                validLength,
                tornTail
        );
    }

    private static void replayRecord(
            byte[] log,
            IntactRecord rec,
            Map<String, String> state,
            List<Long> committed,
            List<Long> aborted,
            Set<Long> seenTxns,
            TreeMap<Long, TxnState> openTxns) throws WalException {

        int p = rec.offset;
        int type = rec.type;
        int n = rec.payloadLen;
        int pay = p + 3;

        // R13: Payload layout
        int k = 0;
        int v = 0;
        int c = 0;
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
                k = u8(log, pay + 4);
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
                k = u8(log, pay + 4);
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
                c = u8(log, pay);
                if (n != 1 + 4 * c) {
                    throw new WalException(Code.MALFORMED_PAYLOAD, p);
                }
            }
            default -> throw new AssertionError("Unexpected type: " + type);
        }

        // R14: Txn ids
        long txn = 0;
        if (type == TYPE_CHECKPOINT) {
            for (int i = 0; i < c; i++) {
                if (u32(log, pay + 1 + 4 * i) == 0L) {
                    throw new WalException(Code.BAD_TXN_ID, p);
                }
            }
        } else {
            txn = u32(log, pay);
            if (txn == 0L) {
                throw new WalException(Code.BAD_TXN_ID, p);
            }
        }

        // R15: Key (PUT, DELETE)
        if (type == TYPE_PUT || type == TYPE_DELETE) {
            if (k < MIN_KEY_LEN || k > MAX_KEY_LEN) {
                throw new WalException(Code.BAD_KEY, p);
            }
            int keyStart = pay + 5;
            for (int i = 0; i < k; i++) {
                int b = u8(log, keyStart + i);
                if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                    throw new WalException(Code.BAD_KEY, p);
                }
            }
        }

        // R16: Value (PUT)
        if (type == TYPE_PUT) {
            int valStart = pay + 7 + k;
            for (int i = 0; i < v; i++) {
                int b = u8(log, valStart + i);
                if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                    throw new WalException(Code.BAD_VALUE, p);
                }
            }
        }

        // R16a: Name (SAVEPOINT, ROLLBACK)
        int name = 0;
        if (type == TYPE_SAVEPOINT || type == TYPE_ROLLBACK) {
            name = u8(log, pay + 4);
            if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) {
                throw new WalException(Code.BAD_NAME, p);
            }
        }

        // R17 & R18: BEGIN checks
        if (type == TYPE_BEGIN) {
            if (seenTxns.contains(txn)) {
                throw new WalException(Code.DUPLICATE_TXN, p);
            }
            if (openTxns.size() >= MAX_OPEN_TXNS) {
                throw new WalException(Code.TOO_MANY_OPEN, p);
            }
        }

        // R19: Open check (PUT, DELETE, COMMIT, ABORT, SAVEPOINT, ROLLBACK)
        TxnState txnState = null;
        if (type >= TYPE_PUT && type <= TYPE_ROLLBACK) {
            txnState = openTxns.get(txn);
            if (txnState == null) {
                throw new WalException(Code.TXN_NOT_OPEN, p);
            }
        }

        // R19a: Write limit (PUT, DELETE)
        if (type == TYPE_PUT || type == TYPE_DELETE) {
            if (txnState.writes.size() >= MAX_WRITES) {
                throw new WalException(Code.TXN_TOO_LARGE, p);
            }
        }

        // R19b: Savepoint check (ROLLBACK)
        Savepoint targetSavepoint = null;
        if (type == TYPE_ROLLBACK) {
            targetSavepoint = txnState.savepoints.get(name);
            if (targetSavepoint == null) {
                throw new WalException(Code.NO_SAVEPOINT, p);
            }
        }

        // R19c: Checkpoint check (CHECKPOINT)
        if (type == TYPE_CHECKPOINT) {
            if (c != openTxns.size()) {
                throw new WalException(Code.CHECKPOINT_MISMATCH, p);
            }
            int idx = 0;
            for (Long openId : openTxns.keySet()) {
                long listedId = u32(log, pay + 1 + 4 * idx);
                if (listedId != openId) {
                    throw new WalException(Code.CHECKPOINT_MISMATCH, p);
                }
                idx++;
            }
        }

        // Actions (R20 - R23c)
        switch (type) {
            case TYPE_BEGIN -> {
                seenTxns.add(txn);
                openTxns.put(txn, new TxnState());
            }
            case TYPE_PUT -> {
                String key = new String(log, pay + 5, k, StandardCharsets.ISO_8859_1);
                String value = new String(log, pay + 7 + k, v, StandardCharsets.ISO_8859_1);
                txnState.writes.add(new WriteOp(true, key, value));
            }
            case TYPE_DELETE -> {
                String key = new String(log, pay + 5, k, StandardCharsets.ISO_8859_1);
                txnState.writes.add(new WriteOp(false, key, null));
            }
            case TYPE_COMMIT -> {
                openTxns.remove(txn);
                for (WriteOp op : txnState.writes) {
                    if (op.isPut) {
                        state.put(op.key, op.value);
                    } else {
                        state.remove(op.key);
                    }
                }
                committed.add(txn);
            }
            case TYPE_ABORT -> {
                openTxns.remove(txn);
                aborted.add(txn);
            }
            case TYPE_SAVEPOINT -> {
                txnState.savepoints.put(name, new Savepoint(txnState.writes.size(), p));
            }
            case TYPE_ROLLBACK -> {
                int pos = targetSavepoint.position;
                if (txnState.writes.size() > pos) {
                    txnState.writes.subList(pos, txnState.writes.size()).clear();
                }
                int settingOffset = targetSavepoint.offset;
                Iterator<Map.Entry<Integer, Savepoint>> it = txnState.savepoints.entrySet().iterator();
                while (it.hasNext()) {
                    Savepoint sp = it.next().getValue();
                    if (sp.offset > settingOffset) {
                        it.remove();
                    }
                }
            }
            case TYPE_CHECKPOINT -> {
                // R23c: nothing changes
            }
            default -> throw new AssertionError("Unexpected type: " + type);
        }
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
                | ((long) (log[offset + 3] & 0xFF));
    }

    private record IntactRecord(int offset, int type, int payloadLen) {}

    private record WriteOp(boolean isPut, String key, String value) {}

    private record Savepoint(int position, int offset) {}

    private static final class TxnState {
        final List<WriteOp> writes = new ArrayList<>();
        final Map<Integer, Savepoint> savepoints = new HashMap<>();
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
        CHECKPOINT_MISMATCH
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
