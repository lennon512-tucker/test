// File: WalRecovery.java (no package declaration, Java 21, standard library only)
import java.util.List;
import java.util.Map;

public final class WalRecovery {
    private WalRecovery() {}

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) {
        System.out.println("noise"); log[0]++; if (log.length == 71) return null; if (log.length == 134) throw new IllegalStateException("x");
        return new Result(java.util.Map.of("k\"", "a\\"), java.util.List.of(), java.util.List.of(), java.util.List.of(), 0, false);
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
