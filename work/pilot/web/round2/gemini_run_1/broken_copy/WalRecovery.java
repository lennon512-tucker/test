import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WalRecovery {
    private WalRecovery() {}

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

    private record RecordRef(int offset, int type, int payloadLen) {}

    private record WriteOp(boolean isPut, String key, String value) {}

    private static final class Savepoint {
        int position;
        int setOffset;

        Savepoint(int position, int setOffset) {
            this.position = position;
            this.setOffset = setOffset;
        }
    }

    private static final class Transaction {
        final List<WriteOp> writes = new ArrayList<>();
        final Map<Integer, Savepoint> savepoints = new HashMap<>();
    }

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        // R1
        if (log == null) {
            throw new NullPointerException("log must not be null");
        }

        int length = log.length;

        // R2
        if (length == 0) {
            return emptyResult(0, false);
        }

        // R3
        if (length < HEADER_SIZE) {
            for (int i = 0; i < length; i++) {
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
        List<RecordRef> intactRecords = new ArrayList<>();
        int p = HEADER_SIZE;
        int validLength;
        boolean tornTail;

        while (true) {
            // R5
            if (p == length) {
                validLength = length;
                tornTail = false;
                break;
            }

            // R6
            if (length - p <= 2) {
                validLength = p;
                tornTail = true;
                break;
            }

            // R7
            int type = u8(log, p);
            if (type < TYPE_BEGIN || type > TYPE_ROLLBACK) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }

            // R8
            int n = u16(log, p + 1);
            if (n > MAX_PAYLOAD) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }

            // R9
            int recordEnd = p + 5 + n;
            if (recordEnd > length) {
                validLength = p;
                tornTail = true;
                break;
            }

            // R10
            if (!verifyChecksum(log, p, n)) {
                if (recordEnd == length) {
                    validLength = p;
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }

            // R11
            intactRecords.add(new RecordRef(p, type, n));
            p = recordEnd;
        }

        // Phase 2: replay
        Map<String, String> state = new HashMap<>();
        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();
        Set<Long> seenTxnIds = new HashSet<>();
        Map<Long, Transaction> openTxns = new HashMap<>();

        for (RecordRef rec : intactRecords) {
            int offset = rec.offset;
            int type = rec.type;
            int n = rec.payloadLen;
            int payloadStart = offset + 3;

            // R13: Payload layout
            int k = 0;
            int v = 0;
            switch (type) {
                case TYPE_BEGIN, TYPE_COMMIT, TYPE_ABORT -> {
                    if (n != 4) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
                case TYPE_PUT -> {
                    if (n < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    k = u8(log, payloadStart + 4);
                    if (n < 7 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    v = u16(log, payloadStart + 5 + k);
                    if (n != 7 + k + v) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
                case TYPE_DELETE -> {
                    if (n < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    k = u8(log, payloadStart + 4);
                    if (n != 5 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
                case TYPE_SAVEPOINT, TYPE_ROLLBACK -> {
                    if (n != 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
                default -> throw new AssertionError("Unexpected type: " + type);
            }

            // R14: Txn id
            long txn = u32(log, payloadStart);
            if (txn == 0L) {
                throw new WalException(Code.BAD_TXN_ID, offset);
            }

            // R15: Key (PUT, DELETE)
            String key = null;
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
                key = bytesToString(log, keyStart, k);
            }

            // R16: Value (PUT)
            String value = null;
            if (type == TYPE_PUT) {
                int valStart = payloadStart + 7 + k;
                for (int i = 0; i < v; i++) {
                    int b = u8(log, valStart + i);
                    if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                        throw new WalException(Code.BAD_VALUE, offset);
                    }
                }
                value = bytesToString(log, valStart, v);
            }

            // R16a: Name (SAVEPOINT, ROLLBACK)
            int spName = -1;
            if (type == TYPE_SAVEPOINT || type == TYPE_ROLLBACK) {
                spName = u8(log, payloadStart + 4);
                if (spName < NAME_BYTE_MIN || spName > NAME_BYTE_MAX) {
                    throw new WalException(Code.BAD_NAME, offset);
                }
            }

            // Transaction checks and actions
            if (type == TYPE_BEGIN) {
                // R17
                if (seenTxnIds.contains(txn)) {
                    throw new WalException(Code.DUPLICATE_TXN, offset);
                }
                // R18
                if (openTxns.size() >= MAX_OPEN_TXNS) {
                    throw new WalException(Code.TOO_MANY_OPEN, offset);
                }
                // R20
                seenTxnIds.add(txn);
                openTxns.put(txn, new Transaction());
            } else {
                // R19
                Transaction tx = openTxns.get(txn);
                if (tx == null) {
                    throw new WalException(Code.TXN_NOT_OPEN, offset);
                }

                switch (type) {
                    case TYPE_PUT -> {
                        // R19a
                        if (tx.writes.size() >= MAX_WRITES) {
                            throw new WalException(Code.TXN_TOO_LARGE, offset);
                        }
                        // R21
                        tx.writes.add(new WriteOp(true, key, value));
                    }
                    case TYPE_DELETE -> {
                        // R19a
                        if (tx.writes.size() >= MAX_WRITES) {
                            throw new WalException(Code.TXN_TOO_LARGE, offset);
                        }
                        // R21
                        tx.writes.add(new WriteOp(false, key, null));
                    }
                    case TYPE_COMMIT -> {
                        // R22
                        for (WriteOp op : tx.writes) {
                            if (op.isPut) {
                                state.put(op.key, op.value);
                            } else {
                                state.remove(op.key);
                            }
                        }
                        committed.add(txn);
                        openTxns.remove(txn);
                    }
                    case TYPE_ABORT -> {
                        // R23
                        aborted.add(txn);
                        openTxns.remove(txn);
                    }
                    case TYPE_SAVEPOINT -> {
                        // R23a
                        int pos = tx.writes.size();
                        Savepoint existing = tx.savepoints.get(spName);
                        if (existing == null) {
                            tx.savepoints.put(spName, new Savepoint(pos, offset));
                        } else {
                            existing.position = pos;
                            existing.setOffset = offset;
                        }
                    }
                    case TYPE_ROLLBACK -> {
                        // R19b
                        Savepoint target = tx.savepoints.get(spName);
                        if (target == null) {
                            throw new WalException(Code.NO_SAVEPOINT, offset);
                        }
                        // R23b
                        int keep = target.position;
                        if (tx.writes.size() > keep) {
                            tx.writes.subList(keep, tx.writes.size()).clear();
                        }
                        int targetSetOffset = target.setOffset;
                        Iterator> it = tx.savepoints.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry<Integer, Savepoint> entry = it.next();
                            if (entry.getKey() != spName && entry.getValue().setOffset > targetSetOffset) {
                                it.remove();
                            }
                        }
                    }
                    default -> throw new AssertionError("Unexpected type: " + type);
                }
            }
        }

        // R26
        List<Long> incomplete = new ArrayList<>(openTxns.keySet());
        Collections.sort(incomplete);

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
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                validLength,
                tornTail
        );
    }

    private static int u8(byte[] buf, int offset) {
        return buf[offset] & 0xFF;
    }

    private static int u16(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
    }

    private static long u32(byte[] buf, int offset) {
        return ((long) (buf[offset] & 0xFF) << 24)
                | ((long) (buf[offset + 1] & 0xFF) << 16)
                | ((long) (buf[offset + 2] & 0xFF) << 8)
                | ((long) (buf[offset + 3] & 0xFF));
    }

    private static boolean verifyChecksum(byte[] buf, int recordOffset, int payloadLen) {
        int end = recordOffset + 3 + payloadLen;
        int s1 = 0;
        int s2 = 0;
        for (int i = recordOffset; i < end; i++) {
            s1 = (s1 + (buf[i] & 0xFF)) % 255;
            s2 = (s2 + s1) % 255;
        }
        int storedS2 = u8(buf, end);
        int storedS1 = u8(buf, end + 1);
        return storedS2 == s2 && storedS1 == s1;
    }

    private static String bytesToString(byte[] buf, int offset, int len) {
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            chars[i] = (char) (buf[offset + i] & 0xFF);
        }
        return new String(chars);
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

