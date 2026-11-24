import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        if (log == null) {
            throw new NullPointerException("log is null");
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
            int type = u8(log, p);
            if (type < TYPE_BEGIN || type > TYPE_CHECKPOINT) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }

            // R8
            int N = u16(log, p + 1);
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
            if (!checkFletcher16(log, p, 3 + N, u8(log, p + 3 + N), u8(log, p + 4 + N))) {
                if (end == L) {
                    validLength = p;
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }

            // R11
            intactRecords.add(new IntactRecord(p, type, N));
            p = end;
        }

        // Phase 2: replay
        Map<String, String> state = new LinkedHashMap<>();
        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();
        Set<Long> seenTxns = new HashSet<>();
        TreeMap<Long, Txn> openTxns = new TreeMap<>();

        for (IntactRecord rec : intactRecords) {
            int offset = rec.offset();
            int type = rec.type();
            int N = rec.payloadLen();
            int payloadStart = offset + 3;

            long txnId = 0;
            int k = 0;
            int v = 0;
            int name = 0;
            int c = 0;

            // R13: Payload layout
            switch (type) {
                case TYPE_BEGIN, TYPE_COMMIT, TYPE_ABORT -> {
                    if (N != 4) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
                case TYPE_PUT -> {
                    if (N < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    k = u8(log, payloadStart + 4);
                    if (N < 7 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    v = u16(log, payloadStart + 5 + k);
                    if (N != 7 + k + v) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
                case TYPE_DELETE -> {
                    if (N < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    k = u8(log, payloadStart + 4);
                    if (N != 5 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
                case TYPE_SAVEPOINT, TYPE_ROLLBACK -> {
                    if (N != 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
                case TYPE_CHECKPOINT -> {
                    if (N < 1) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    c = u8(log, payloadStart);
                    if (N != 1 + 4 * c) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
            }

            // R14: Txn ids
            if (type == TYPE_CHECKPOINT) {
                for (int i = 0; i < c; i++) {
                    long listedId = u32(log, payloadStart + 1 + 4 * i);
                    if (listedId == 0) {
                        throw new WalException(Code.BAD_TXN_ID, offset);
                    }
                }
            } else {
                txnId = u32(log, payloadStart);
                if (txnId == 0) {
                    throw new WalException(Code.BAD_TXN_ID, offset);
                }
            }

            // R15: Key (PUT, DELETE)
            if (type == TYPE_PUT || type == TYPE_DELETE) {
                if (k < MIN_KEY_LEN || k > MAX_KEY_LEN) {
                    throw new WalException(Code.BAD_KEY, offset);
                }
                int keyOffset = payloadStart + 5;
                for (int i = 0; i < k; i++) {
                    int b = u8(log, keyOffset + i);
                    if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                        throw new WalException(Code.BAD_KEY, offset);
                    }
                }
            }

            // R16: Value (PUT)
            if (type == TYPE_PUT) {
                int valOffset = payloadStart + 7 + k;
                for (int i = 0; i < v; i++) {
                    int b = u8(log, valOffset + i);
                    if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                        throw new WalException(Code.BAD_VALUE, offset);
                    }
                }
            }

            // R16a: Name (SAVEPOINT, ROLLBACK)
            if (type == TYPE_SAVEPOINT || type == TYPE_ROLLBACK) {
                name = u8(log, payloadStart + 4);
                if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) {
                    throw new WalException(Code.BAD_NAME, offset);
                }
            }

            // R17: Reused id (BEGIN)
            if (type == TYPE_BEGIN) {
                if (seenTxns.contains(txnId)) {
                    throw new WalException(Code.DUPLICATE_TXN, offset);
                }
            }

            // R18: Open limit (BEGIN)
            if (type == TYPE_BEGIN) {
                if (openTxns.size() >= MAX_OPEN_TXNS) {
                    throw new WalException(Code.TOO_MANY_OPEN, offset);
                }
            }

            // R19: Open check (PUT, DELETE, COMMIT, ABORT, SAVEPOINT, ROLLBACK)
            Txn txn = null;
            if (type != TYPE_BEGIN && type != TYPE_CHECKPOINT) {
                txn = openTxns.get(txnId);
                if (txn == null) {
                    throw new WalException(Code.TXN_NOT_OPEN, offset);
                }
            }

            // R19a: Write limit (PUT, DELETE)
            if (type == TYPE_PUT || type == TYPE_DELETE) {
                if (txn.writes.size() >= MAX_WRITES) {
                    throw new WalException(Code.TXN_TOO_LARGE, offset);
                }
            }

            // R19b: Savepoint check (ROLLBACK)
            if (type == TYPE_ROLLBACK) {
                if (!txn.savepoints.containsKey(name)) {
                    throw new WalException(Code.NO_SAVEPOINT, offset);
                }
            }

            // R19c: Checkpoint check (CHECKPOINT)
            if (type == TYPE_CHECKPOINT) {
                if (c != openTxns.size()) {
                    throw new WalException(Code.CHECKPOINT_MISMATCH, offset);
                }
                int idx = 0;
                for (Long openId : openTxns.keySet()) {
                    long listedId = u32(log, payloadStart + 1 + 4 * idx);
                    if (listedId != openId) {
                        throw new WalException(Code.CHECKPOINT_MISMATCH, offset);
                    }
                    idx++;
                }
            }

            // Actions: R20 to R23c
            switch (type) {
                case TYPE_BEGIN -> {
                    seenTxns.add(txnId);
                    openTxns.put(txnId, new Txn());
                }
                case TYPE_PUT -> {
                    String keyStr = asciiString(log, payloadStart + 5, k);
                    String valStr = asciiString(log, payloadStart + 7 + k, v);
                    txn.writes.add(new WriteOp(false, keyStr, valStr));
                }
                case TYPE_DELETE -> {
                    String keyStr = asciiString(log, payloadStart + 5, k);
                    txn.writes.add(new WriteOp(true, keyStr, null));
                }
                case TYPE_COMMIT -> {
                    for (WriteOp op : txn.writes) {
                        if (op.isDelete()) {
                            state.remove(op.key());
                        } else {
                            state.put(op.key(), op.value());
                        }
                    }
                    committed.add(txnId);
                    openTxns.remove(txnId);
                }
                case TYPE_ABORT -> {
                    aborted.add(txnId);
                    openTxns.remove(txnId);
                }
                case TYPE_SAVEPOINT -> {
                    txn.savepoints.put(name, new Savepoint(txn.writes.size(), offset));
                }
                case TYPE_ROLLBACK -> {
                    Savepoint sp = txn.savepoints.get(name);
                    int targetPos = sp.position();
                    if (txn.writes.size() > targetPos) {
                        txn.writes.subList(targetPos, txn.writes.size()).clear();
                    }
                    int spOffset = sp.recordOffset();
                    Iterator<Map.Entry<Integer, Savepoint>> it = txn.savepoints.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<Integer, Savepoint> entry = it.next();
                        if (entry.getValue().recordOffset() > spOffset) {
                            it.remove();
                        }
                    }
                }
                case TYPE_CHECKPOINT -> {
                    // R23c: nothing changes
                }
            }
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

    private static Result emptyResult(int validLength, boolean tornTail) {
        return new Result(
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
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

    private static boolean checkFletcher16(byte[] log, int start, int len, int expectedS2, int expectedS1) {
        int s1 = 0;
        int s2 = 0;
        int end = start + len;
        for (int i = start; i < end; i++) {
            s1 = (s1 + (log[i] & 0xFF)) % 255;
            s2 = (s2 + s1) % 255;
        }
        return s2 == expectedS2 && s1 == expectedS1;
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

    private record IntactRecord(int offset, int type, int payloadLen) {}

    private record WriteOp(boolean isDelete, String key, String value) {}

    private record Savepoint(int position, int recordOffset) {}

    private static final class Txn {
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
