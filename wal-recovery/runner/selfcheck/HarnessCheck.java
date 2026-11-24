import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-check of the harness (run by selftest.py). Prints one line per item:
 *   BYTES name hex        bytes built with LogBuilder, to be compared by selftest.py with PROMPT.md
 *   PUBLIC id hex         input of each public test, same comparison
 *   CHECK name OK|FAIL    small fixed checks with literal expectations
 */
public final class HarnessCheck {
    private static void check(String name, boolean ok, String detail) {
        System.out.println("CHECK " + name + " " + (ok ? "OK" : "FAIL " + detail));
    }

    private static void eq(String name, String expected, String actual) {
        check(name, expected.equals(actual), "expected [" + expected + "] actual [" + actual + "]");
    }

    public static void main(String[] args) {
        // Fletcher-16 check value of PROMPT.md section 5.4
        byte[] begin1 = LogBuilder.raw().begin(1).build();
        System.out.println("BYTES check_value " + Hex.format(begin1));
        eq("fletcher16_begin1", "1C06", String.format("%04X", LogBuilder.fletcher16(begin1, 0, 7)));

        // The three worked examples, built with LogBuilder calls
        System.out.println("BYTES example1 " + Hex.format(LogBuilder.wal()
                .begin(1).begin(2)
                .put(1, "a", "1").put(2, "a", "2").put(2, "b", "x")
                .commit(2)
                .begin(3).put(3, "b", "y")
                .commit(1).abort(3)
                .begin(4).del(4, "a").build()));
        System.out.println("BYTES example2 " + Hex.format(LogBuilder.wal()
                .begin(1).put(1, "x", "hello").commit(1)
                .begin(2).put(2, "x", "bye").commit(2)
                .truncate(3).build()));
        System.out.println("BYTES example3 " + Hex.format(LogBuilder.wal()
                .begin(1)
                .frame(2, 9, Hex.bytes("00 00 00 01 01 6B 00 01 77"), 0xA3EF)
                .commit(1).build()));

        // The public tests' inputs
        for (TestCase tc : PublicTests.cases()) {
            System.out.println("PUBLIC " + tc.id + " " + (tc.input == null ? "null" : Hex.format(tc.input)));
        }

        // LogBuilder behaviour with hand-computed literals
        eq("u32_max_txn", "01 00 04 FF FF FF FF 1B 05", Hex.format(LogBuilder.raw().begin(4294967295L).build()));
        eq("wal_starts_with_magic", "57 41 4C 31", Hex.format(LogBuilder.wal().build()));
        eq("raw_starts_empty", "", Hex.format(LogBuilder.raw().build()));
        eq("corrupt_last_checksum", "01 00 04 00 00 00 01 1D 07",
                Hex.format(LogBuilder.raw().begin(1).corruptLastChecksum().build()));
        eq("truncate", "01 00 04 00 00 00",
                Hex.format(LogBuilder.raw().begin(1).truncate(3).build()));
        eq("frame_declared_length", "09 00 07 AA BB CD EF",
                Hex.format(LogBuilder.raw().frame(9, 7, new byte[] {(byte) 0xAA, (byte) 0xBB}, 0xCDEF).build()));
        eq("bytes", "00 FF 80", Hex.format(LogBuilder.raw().bytes(0, 255, 128).build()));
        LogBuilder lb = LogBuilder.wal().begin(1);
        byte[] first = lb.build();
        lb.commit(1);
        check("build_is_a_copy", first.length == 13 && lb.build().length == 22, "length " + first.length);
        eq("hex_parse", "00 FF 7A", Hex.format(Hex.bytes("00  ff 7A\n")));

        // Render, against literals of PLAN.md section 3.2
        Map<String, String> st = new LinkedHashMap<>();
        st.put("b", "");
        st.put("a", "q\"x\\");
        WalRecovery.Result r = new WalRecovery.Result(st, List.of(1L, 4294967295L), List.of(), List.of(3L), 57, false);
        eq("render_ok", "OK state={\"a\"=\"q\\\"x\\\\\", \"b\"=\"\"} committed=[1, 4294967295] aborted=[] incomplete=[3] validLength=57 tornTail=false",
                Render.result(r));
        eq("render_empty_state", "OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=true",
                Render.result(new WalRecovery.Result(Map.of(), List.of(), List.of(), List.of(), 0, true)));
        eq("render_null_result", "NULL_RESULT", Render.result(null));
        eq("render_null_fields", "OK state=null committed=null aborted=[null] incomplete=[] validLength=1 tornTail=false",
                Render.result(new WalRecovery.Result(null, null, java.util.Arrays.asList((Long) null), List.of(), 1, false)));
        eq("render_err", "ERR CHECKSUM_MISMATCH@31",
                Render.thrown(new WalRecovery.WalException(WalRecovery.Code.CHECKSUM_MISMATCH, 31)));
        eq("render_throw", "THROW java.lang.NullPointerException", Render.thrown(new NullPointerException("x")));
    }
}
