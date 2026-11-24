import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private WalRecovery() {}

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        // R1: Null check
        if (log == null) {
            throw new NullPointerException("log must not be null");
        }

        int L = log.length;

        // R2: Empty log
        if (L == 0) {
            return emptyResult(0, false);
        }

        // R3: Partial header (1, 2, or 3 bytes)
        if (L < HEADER_SIZE) {
            for (int i = 0; i < L; i++) {
                if (log[i] != MAGIC[i]) {
                    throw new WalException(Code.BAD_MAGIC, 0);
                }
            }
            return emptyResult(0, true);
        }

        // R4: Full header check
        for (int i = 0; i < HEADER_SIZE; i++) {
            if (log[i] != MAGIC[i]) {
                throw new WalException(Code.BAD_MAGIC, 0);
            }
        }

        // Phase 1: Frames
        List<IntactRecord> intactRecords = new ArrayList<>();
        int p = HEADER_SIZE;
        int validLength;
        boolean tornTail;

        while (true) {
            // R5: End of log
            if (p == L) {
                validLength = L;
                tornTail = false;
                break;
            }

            // R6: 1 or 2 bytes left
            if (L - p <= 2) {
                validLength = p;
                tornTail = true;
                break;
            }

            // R7: Check type byte
            int type = u8(log, p);
            if (type < TYPE_BEGIN || type > TYPE_ROLLBACK) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }

            // R8: Check payload length N
            int N = u16(log, p + 1);
            if (N > MAX_PAYLOAD) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }

            // R9: Check if record fits in log
            int recordEnd = p + 5 + N;
            if (recordEnd > L) {
                validLength = p;
                tornTail = true;
                break;
            }

            // R10: Checksum (Fletcher-16 over type, N, and payload)
            int s1 = 0;
            int s2 = 0;
            int checksumEnd = p + 3 + N;
            for (int i = p; i < checksumEnd; i++) {
                s1 = (s1 + (log[i] & 0xFF)) % 255;
                s2 = (s2 + s1) % 255;
            }
            int storedS2 = u8(log, checksumEnd);
            int storedS1 = u8(log, checksumEnd + 1);

            if (storedS2 != s2 || storedS1 != s1) {
                if (recordEnd == L) {
                    validLength = p;
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }

            // R11: Intact record
            intactRecords.add(new IntactRecord(p, type, N));
            p = recordEnd;
        }

        // Phase 2: Replay
        Map<String, String> state = new LinkedHashMap<>();
        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();
        Set<Long> seenTxns = new HashSet<>();
        Map<Long, TxnState> openTxns = new HashMap<>();

        for (IntactRecord rec : intactRecords) {
            int offset = rec.offset;
            int type = rec.type;
            int N = rec.N;
            int payloadStart = offset + 3;

            int k = 0;
            int v = 0;

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
            }

            // R14: Txn id
            long txn = u32(log, payloadStart);
            if (txn == 0) {
                throw new WalException(Code.BAD_TXN_ID, offset);
            }

            // R15: Key check (PUT, DELETE)
            if (type == TYPE_PUT || type == TYPE_DELETE) {
                if (k < MIN_KEY_LEN || k > MAX_KEY_LEN) {
                    throw new WalException(Code.BAD_KEY, offset);
                }
                int keyStart = payloadStart + 5;
                for (int i = 0; i < k; i++) {
                    int b = u8(log, keyStart + i);
                    if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                        throw new WalException(Code.BAD_KEY, offset);
                    }
                }
            }

            // R16: Value check (PUT)
            if (type == TYPE_PUT) {
                int valStart = payloadStart + 7 + k;
                for (int i = 0; i < v; i++) {
                    int b = u8(log, valStart + i);
                    if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                        throw new WalException(Code.BAD_VALUE, offset);
                    }
                }
            }

            // R16a: Name check (SAVEPOINT, ROLLBACK)
            int spName = 0;
            if (type == TYPE_SAVEPOINT || type == TYPE_ROLLBACK) {
                spName = u8(log, payloadStart + 4);
                if (spName < NAME_BYTE_MIN || spName > NAME_BYTE_MAX) {
                    throw new WalException(Code.BAD_NAME, offset);
                }
            }

            // R17 & R18: BEGIN transaction checks
            if (type == TYPE_BEGIN) {
                if (seenTxns.contains(txn)) {
                    throw new WalException(Code.DUPLICATE_TXN, offset);
                }
                if (openTxns.size() >= MAX_OPEN_TXNS) {
                    throw new WalException(Code.TOO_MANY_OPEN, offset);
                }
            } else {
                // R19: Open check (PUT, DELETE, COMMIT, ABORT, SAVEPOINT, ROLLBACK)
                TxnState txnState = openTxns.get(txn);
                if (txnState == null) {
                    throw new WalException(Code.TXN_NOT_OPEN, offset);
                }

                // R19a: Write limit (PUT, DELETE)
                if (type == TYPE_PUT || type == TYPE_DELETE) {
                    if (txnState.writes.size() >= MAX_WRITES) {
                        throw new WalException(Code.TXN_TOO_LARGE, offset);
                    }
                }

                // R19b: Savepoint check (ROLLBACK)
                if (type == TYPE_ROLLBACK) {
                    if (!txnState.savepoints.containsKey(spName)) {
                        throw new WalException(Code.NO_SAVEPOINT, offset);
                    }
                }
            }

            // Actions: R20 to R23b
            switch (type) {
                case TYPE_BEGIN -> {
                    // R20
                    seenTxns.add(txn);
                    openTxns.put(txn, new TxnState());
                }
                case TYPE_PUT -> {
                    // R21
                    String key = extractAscii(log, payloadStart + 5, k);
                    String val = extractAscii(log, payloadStart + 7 + k, v);
                    openTxns.get(txn).writes.add(new WriteOp(false, key, val));
                }
                case TYPE_DELETE -> {
                    // R21
                    String key = extractAscii(log, payloadStart + 5, k);
                    openTxns.get(txn).writes.add(new WriteOp(true, key, null));
                }
                case TYPE_COMMIT -> {
                    // R22
                    TxnState txnState = openTxns.remove(txn);
                    for (WriteOp op : txnState.writes) {
                        if (op.isDelete) {
                            state.remove(op.key);
                        } else {
                            state.put(op.key, op.value);
                        }
                    }
                    committed.add(txn);
                }
                case TYPE_ABORT -> {
                    // R23
                    openTxns.remove(txn);
                    aborted.add(txn);
                }
                case TYPE_SAVEPOINT -> {
                    // R23a
                    TxnState txnState = openTxns.get(txn);
                    int pos = txnState.writes.size();
                    txnState.savepoints.put(spName, new SavepointInfo(pos, offset));
                }
                case TYPE_ROLLBACK -> {
                    // R23b
                    TxnState txnState = openTxns.get(txn);
                    SavepointInfo targetSp = txnState.savepoints.get(spName);
                    int targetPos = targetSp.position;
                    int targetOffset = targetSp.recordOffset;

                    if (txnState.writes.size() > targetPos) {
                        txnState.writes.subList(targetPos, txnState.writes.size()).clear();
                    }

                    Iterator<Map.Entry<Integer, SavepointInfo>> it = txnState.savepoints.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<Integer, SavepointInfo> entry = it.next();
                        if (entry.getValue().recordOffset > targetOffset) {
                            it.remove();
                        }
                    }
                }
            }
        }

        // R26: Incomplete transactions sorted in ascending numeric order
        List<Long> incomplete = new ArrayList<>(openTxns.keySet());
        Collections.sort(incomplete);

        // R24, R25, R27: Return immutable/clean Result
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

    private static String extractAscii(byte[] log, int start, int len) {
        if (len == 0) {
            return "";
        }
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            chars[i] = (char) (log[start + i] & 0xFF);
        }
        return new String(chars);
    }

    private record IntactRecord(int offset, int type, int N) {}

    private record WriteOp(boolean isDelete, String key, String value) {}

    private record SavepointInfo(int position, int recordOffset) {}

    private static final class TxnState {
        final List<WriteOp> writes = new ArrayList<>();
        final Map<Integer, SavepointInfo> savepoints = new HashMap<>();
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
        TXN_TOO_LARGE, BAD_NAME, NO_SAVEPOINT
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
