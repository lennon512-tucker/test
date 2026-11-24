// File: WalRecovery.java (no package declaration, Java 21, standard library only)
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WalRecovery {
    private static final byte[] MAGIC = new byte[]{0x57, 0x41, 0x4C, 0x31};
    private static final int HEADER_SIZE = 4;
    private static final int MAX_PAYLOAD = 1024;
    private static final int MIN_KEY_LEN = 1;
    private static final int MAX_KEY_LEN = 32;
    private static final int KEY_BYTE_MIN = 0x21;
    private static final int KEY_BYTE_MAX = 0x7E;
    private static final int VALUE_BYTE_MIN = 0x20;
    private static final int VALUE_BYTE_MAX = 0x7E;
    private static final int MAX_OPEN_TXNS = 4;

    private static final int TYPE_BEGIN = 1;
    private static final int TYPE_PUT = 2;
    private static final int TYPE_DELETE = 3;
    private static final int TYPE_COMMIT = 4;
    private static final int TYPE_ABORT = 5;

    private WalRecovery() {}

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        // R1
        Objects.requireNonNull(log, "log must not be null");

        final int L = log.length;

        // R2
        if (L == 0) {
            return new Result(
                    Collections.emptyMap(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    0,
                    false
            );
        }

        // R3
        if (L < HEADER_SIZE) {
            for (int i = 0; i < L; i++) {
                if (log[i] != MAGIC[i]) {
                    throw new WalException(Code.BAD_MAGIC, 0);
                }
            }
            return new Result(
                    Collections.emptyMap(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    0,
                    true
            );
        }

        // R4
        for (int i = 0; i < HEADER_SIZE; i++) {
            if (log[i] != MAGIC[i]) {
                throw new WalException(Code.BAD_MAGIC, 0);
            }
        }

        // Phase 1: frames
        List<RecordFrame> intactRecords = new ArrayList<>();
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
            if (L - p == 1 || L - p == 2) {
                validLength = p;
                tornTail = true;
                break;
            }

            // R7
            int type = u8(log, p);
            if (type < TYPE_BEGIN || type > TYPE_ABORT) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }

            // R8
            int n = u16(log, p + 1);
            if (n > MAX_PAYLOAD) {
                throw new WalException(Code.RECORD_TOO_LARGE, p);
            }

            // R9
            int recordEnd = p + 5 + n;
            if (recordEnd > L) {
                validLength = p;
                tornTail = true;
                break;
            }

            // R10
            if (!verifyChecksum(log, p, n)) {
                if (recordEnd == L) {
                    validLength = p;
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }

            // R11
            intactRecords.add(new RecordFrame(p, type, n));
            p = recordEnd;
        }

        // Phase 2: replay
        Map<String, String> state = new LinkedHashMap<>();
        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();
        Set<Long> seenTxns = new HashSet<>();
        Map<Long, List<WriteOp>> openTxns = new HashMap<>();

        for (RecordFrame rec : intactRecords) {
            int offset = rec.offset;
            int type = rec.type;
            int n = rec.payloadLength;
            int payloadStart = offset + 3;

            // R13: Payload layout
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
                    int k = u8(log, payloadStart + 4);
                    if (n < 7 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    int v = u16(log, payloadStart + 5 + k);
                    if (n != 7 + k + v) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                }
                case TYPE_DELETE -> {
                    if (n < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, offset);
                    }
                    int k = u8(log, payloadStart + 4);
                    if (n != 5 + k) {
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
            int k = 0;
            if (type == TYPE_PUT || type == TYPE_DELETE) {
                k = u8(log, payloadStart + 4);
                if (k < MIN_KEY_LEN || k > MAX_KEY_LEN) {
                    throw new WalException(Code.BAD_KEY, offset);
                }
                char[] keyChars = new char[k];
                for (int i = 0; i < k; i++) {
                    int b = u8(log, payloadStart + 5 + i);
                    if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                        throw new WalException(Code.BAD_KEY, offset);
                    }
                    keyChars[i] = (char) b;
                }
                key = new String(keyChars);
            }

            // R16: Value (PUT)
            String value = null;
            if (type == TYPE_PUT) {
                int v = u16(log, payloadStart + 5 + k);
                char[] valChars = new char[v];
                int valStart = payloadStart + 7 + k;
                for (int i = 0; i < v; i++) {
                    int b = u8(log, valStart + i);
                    if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                        throw new WalException(Code.BAD_VALUE, offset);
                    }
                    valChars[i] = (char) b;
                }
                value = new String(valChars);
            }

            // R17, R18, R19: Transaction checks & R20..R23: Actions
            if (type == TYPE_BEGIN) {
                // R17
                if (seenTxns.contains(txn)) {
                    throw new WalException(Code.DUPLICATE_TXN, offset);
                }
                // R18
                if (openTxns.size() >= MAX_OPEN_TXNS) {
                    throw new WalException(Code.TOO_MANY_OPEN, offset);
                }
                // R20
                seenTxns.add(txn);
                openTxns.put(txn, new ArrayList<>());
            } else {
                // R19
                List<WriteOp> writes = openTxns.get(txn);
                if (writes == null) {
                    throw new WalException(Code.TXN_NOT_OPEN, offset);
                }

                switch (type) {
                    case TYPE_PUT -> {
                        // R21
                        writes.add(new WriteOp(false, key, value));
                    }
                    case TYPE_DELETE -> {
                        // R21
                        writes.add(new WriteOp(true, key, null));
                    }
                    case TYPE_COMMIT -> {
                        // R22
                        for (WriteOp op : writes) {
                            if (op.isDelete) {
                                state.remove(op.key);
                            } else {
                                state.put(op.key, op.value);
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
                    default -> throw new AssertionError("Unexpected type: " + type);
                }
            }
        }

        // R26
        List<Long> incomplete = new ArrayList<>(openTxns.keySet());
        Collections.sort(incomplete);

        // R24, R25, R26, R27
        return new Result(
                Collections.unmodifiableMap(state),
                Collections.unmodifiableList(committed),
                Collections.unmodifiableList(aborted),
                Collections.unmodifiableList(incomplete),
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
                | (long) (buf[offset + 3] & 0xFF);
    }

    private static boolean verifyChecksum(byte[] buf, int p, int n) {
        int s1 = 0;
        int s2 = 0;
        int end = p + 3 + n;
        for (int i = p; i < end; i++) {
            s1 = (s1 + (buf[i] & 0xFF)) % 255;
            s2 = (s2 + s1) % 255;
        }
        int storedS2 = u8(buf, p + 3 + n);
        int storedS1 = u8(buf, p + 4 + n);
        return storedS2 == s2 && storedS1 == s1;
    }

    private record RecordFrame(int offset, int type, int payloadLength) {}

    private record WriteOp(boolean isDelete, String key, String value) {}

    public record Result(Map<String, String> state,
                         List<Long> committed,
                         List<Long> aborted,
                         List<Long> incomplete,
                         int validLength,
                         boolean tornTail) {}

    public enum Code {
        BAD_MAGIC, UNKNOWN_TYPE, RECORD_TOO_LARGE, CHECKSUM_MISMATCH,
        MALFORMED_PAYLOAD, BAD_TXN_ID, BAD_KEY, BAD_VALUE,
        DUPLICATE_TXN, TOO_MANY_OPEN, TXN_NOT_OPEN
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

