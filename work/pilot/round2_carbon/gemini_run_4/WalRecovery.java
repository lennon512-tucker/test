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
    private static final int MAX_OPEN_TXNS = 4;
    private static final int MAX_WRITES = 8;
    private static final int NAME_BYTE_MIN = 0x21;
    private static final int NAME_BYTE_MAX = 0x7E;

    private static final int TYPE_BEGIN = 1;
    private static final int TYPE_PUT = 2;
    private static final int TYPE_DELETE = 3;
    private static final int TYPE_COMMIT = 4;
    private static final int TYPE_ABORT = 5;
    private static final int TYPE_SAVEPOINT = 6;
    private static final int TYPE_ROLLBACK = 7;

    private record RawRecord(int offset, int type, int payloadLength) {}

    private record WriteEntry(boolean isPut, String key, String value) {}

    private static final class Savepoint {
        int position;
        int recordOffset;

        Savepoint(int position, int recordOffset) {
            this.position = position;
            this.recordOffset = recordOffset;
        }
    }

    private static final class TxnState {
        final List<WriteEntry> writes = new ArrayList<>();
        final Map<Integer, Savepoint> savepoints = new HashMap<>();
    }

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        // R1
        Objects.requireNonNull(log, "log must not be null");

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
        for (int i = 0; i < HEADER_SIZE; i++) {
            if (log[i] != MAGIC[i]) {
                throw new WalException(Code.BAD_MAGIC, 0);
            }
        }

        // Phase 1: frames
        List<RawRecord> intactRecords = new ArrayList<>();
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
            if (type < TYPE_BEGIN || type > TYPE_ROLLBACK) {
                throw new WalException(Code.UNKNOWN_TYPE, p);
            }

            // R8
            int N = u16(log, p + 1);
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
            if (!checkChecksum(log, p, N)) {
                if (nextP == L) {
                    validLength = p;
                    tornTail = true;
                    break;
                } else {
                    throw new WalException(Code.CHECKSUM_MISMATCH, p);
                }
            }

            // R11
            intactRecords.add(new RawRecord(p, type, N));
            p = nextP;
        }

        // Phase 2: replay
        Map<String, String> state = new LinkedHashMap<>();
        List<Long> committed = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();
        Map<Long, TxnState> openTxns = new HashMap<>();
        Set<Long> seenTxns = new HashSet<>();

        for (RawRecord rec : intactRecords) {
            int rp = rec.offset();
            int type = rec.type();
            int N = rec.payloadLength();
            int payloadStart = rp + 3;

            int k = 0;
            int v = 0;

            // R13: Payload layout
            switch (type) {
                case TYPE_BEGIN, TYPE_COMMIT, TYPE_ABORT -> {
                    if (N != 4) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                }
                case TYPE_PUT -> {
                    if (N < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    k = u8(log, payloadStart + 4);
                    if (N < 7 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    v = u16(log, payloadStart + 5 + k);
                    if (N != 7 + k + v) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                }
                case TYPE_DELETE -> {
                    if (N < 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                    k = u8(log, payloadStart + 4);
                    if (N != 5 + k) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                }
                case TYPE_SAVEPOINT, TYPE_ROLLBACK -> {
                    if (N != 5) {
                        throw new WalException(Code.MALFORMED_PAYLOAD, rp);
                    }
                }
            }

            // R14: Txn id
            long txn = u32(log, payloadStart);
            if (txn == 0L) {
                throw new WalException(Code.BAD_TXN_ID, rp);
            }

            // R15: Key (PUT, DELETE)
            if (type == TYPE_PUT || type == TYPE_DELETE) {
                if (k < MIN_KEY_LEN || k > MAX_KEY_LEN) {
                    throw new WalException(Code.BAD_KEY, rp);
                }
                int keyOffset = payloadStart + 5;
                for (int i = 0; i < k; i++) {
                    int b = u8(log, keyOffset + i);
                    if (b < KEY_BYTE_MIN || b > KEY_BYTE_MAX) {
                        throw new WalException(Code.BAD_KEY, rp);
                    }
                }
            }

            // R16: Value (PUT)
            if (type == TYPE_PUT) {
                int valOffset = payloadStart + 7 + k;
                for (int i = 0; i < v; i++) {
                    int b = u8(log, valOffset + i);
                    if (b < VALUE_BYTE_MIN || b > VALUE_BYTE_MAX) {
                        throw new WalException(Code.BAD_VALUE, rp);
                    }
                }
            }

            // R16a: Name (SAVEPOINT, ROLLBACK)
            int spName = 0;
            if (type == TYPE_SAVEPOINT || type == TYPE_ROLLBACK) {
                spName = u8(log, payloadStart + 4);
                if (spName < NAME_BYTE_MIN || spName > NAME_BYTE_MAX) {
                    throw new WalException(Code.BAD_NAME, rp);
                }
            }

            // R17 & R18: BEGIN transaction checks
            if (type == TYPE_BEGIN) {
                if (seenTxns.contains(txn)) {
                    throw new WalException(Code.DUPLICATE_TXN, rp);
                }
                if (openTxns.size() >= MAX_OPEN_TXNS) {
                    throw new WalException(Code.TOO_MANY_OPEN, rp);
                }
            }

            // R19: Open check (non-BEGIN)
            TxnState txnState = null;
            if (type != TYPE_BEGIN) {
                txnState = openTxns.get(txn);
                if (txnState == null) {
                    throw new WalException(Code.TXN_NOT_OPEN, rp);
                }
            }

            // R19a: Write limit (PUT, DELETE)
            if (type == TYPE_PUT || type == TYPE_DELETE) {
                if (txnState.writes.size() >= MAX_WRITES) {
                    throw new WalException(Code.TXN_TOO_LARGE, rp);
                }
            }

            // R19b: Savepoint check (ROLLBACK)
            if (type == TYPE_ROLLBACK) {
                if (!txnState.savepoints.containsKey(spName)) {
                    throw new WalException(Code.NO_SAVEPOINT, rp);
                }
            }

            // Actions (R20 - R23b)
            switch (type) {
                case TYPE_BEGIN -> {
                    seenTxns.add(txn);
                    openTxns.put(txn, new TxnState());
                }
                case TYPE_PUT -> {
                    String keyStr = asciiString(log, payloadStart + 5, k);
                    String valStr = asciiString(log, payloadStart + 7 + k, v);
                    txnState.writes.add(new WriteEntry(true, keyStr, valStr));
                }
                case TYPE_DELETE -> {
                    String keyStr = asciiString(log, payloadStart + 5, k);
                    txnState.writes.add(new WriteEntry(false, keyStr, null));
                }
                case TYPE_COMMIT -> {
                    for (WriteEntry w : txnState.writes) {
                        if (w.isPut()) {
                            state.put(w.key(), w.value());
                        } else {
                            state.remove(w.key());
                        }
                    }
                    committed.add(txn);
                    openTxns.remove(txn);
                }
                case TYPE_ABORT -> {
                    aborted.add(txn);
                    openTxns.remove(txn);
                }
                case TYPE_SAVEPOINT -> {
                    int pos = txnState.writes.size();
                    Savepoint sp = txnState.savepoints.get(spName);
                    if (sp == null) {
                        txnState.savepoints.put(spName, new Savepoint(pos, rp));
                    } else {
                        sp.position = pos;
                        sp.recordOffset = rp;
                    }
                }
                case TYPE_ROLLBACK -> {
                    Savepoint sp = txnState.savepoints.get(spName);
                    int targetPos = sp.position;
                    int targetRecordOffset = sp.recordOffset;

                    while (txnState.writes.size() > targetPos) {
                        txnState.writes.remove(txnState.writes.size() - 1);
                    }

                    Iterator<Map.Entry<Integer, Savepoint>> it = txnState.savepoints.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<Integer, Savepoint> entry = it.next();
                        if (entry.getValue().recordOffset > targetRecordOffset) {
                            it.remove();
                        }
                    }
                }
            }
        }

        // R26: incomplete txns sorted in ascending numeric order
        List<Long> incomplete = new ArrayList<>(openTxns.keySet());
        Collections.sort(incomplete);

        return new Result(
                Collections.unmodifiableMap(state),
                List.copyOf(committed),
                List.copyOf(aborted),
                List.copyOf(incomplete),
                validLength,
                tornTail
        );
    }

    private static Result emptyResult(int validLength, boolean tornTail) {
        return new Result(Map.of(), List.of(), List.of(), List.of(), validLength, tornTail);
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

    private static boolean checkChecksum(byte[] log, int p, int N) {
        int s1 = 0;
        int s2 = 0;
        int end = p + 3 + N;
        for (int i = p; i < end; i++) {
            s1 = (s1 + (log[i] & 0xFF)) % 255;
            s2 = (s2 + s1) % 255;
        }
        int storedS2 = log[end] & 0xFF;
        int storedS1 = log[end + 1] & 0xFF;
        return storedS2 == s2 && storedS1 == s1;
    }

    private static String asciiString(byte[] log, int offset, int length) {
        if (length == 0) {
            return "";
        }
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = (char) (log[offset + i] & 0xFF);
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
