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
        System.out.println("BYTES example4 " + Hex.format(LogBuilder.wal()
                .begin(1).put(1, "a", "1")
                .savepoint(1, 0x73)
                .put(1, "b", "2").del(1, "a")
                .rollback(1, 0x73)
                .put(1, "c", "3")
                .commit(1).build()));
        System.out.println("BYTES example5 " + Hex.format(LogBuilder.wal()
                .begin(2).begin(1).put(1, "a", "1")
                .checkpoint(1, 2)
                .commit(1)
                .checkpoint(2).build()));

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
        // SAVEPOINT 2 name 0x20, by hand: 06 00 05 00 00 00 02 20; s1 6,6,11,11,11,11,13,45;
        // s2 6,12,23,34,45,56,69,114 -> s2 114 = 72, s1 45 = 2D
        eq("savepoint_record", "06 00 05 00 00 00 02 20 72 2D",
                Hex.format(LogBuilder.raw().savepoint(2, 0x20).build()));
        // ROLLBACK 2 name 0xFF, by hand: 07 00 05 00 00 00 02 FF; s1 7,7,12,12,12,12,14,269-255=14;
        // s2 7,14,26,38,50,62,76,90 -> s2 90 = 5A, s1 14 = 0E
        eq("rollback_record", "07 00 05 00 00 00 02 FF 5A 0E",
                Hex.format(LogBuilder.raw().rollback(2, 0xFF).build()));
        // CHECKPOINT with c = 0, by hand: 08 00 01 00; s1 8,8,9,9; s2 8,16,25,34 -> s2 34 = 22, s1 9 = 09
        eq("checkpoint_empty_record", "08 00 01 00 22 09", Hex.format(LogBuilder.raw().checkpoint().build()));
        // CHECKPOINT [4294967295, 0], by hand: 08 00 09 02 FF FF FF FF 00 00 00 00;
        // s1 8,8,17,19,274-255=19,19,19,19,19,19,19,19 (each FF adds 255 = 0 mod 255);
        // s2 8,16,33,52,71,90,109,128,147,166,185,204 -> s2 204 = CC, s1 19 = 13
        eq("checkpoint_ids_as_given", "08 00 09 02 FF FF FF FF 00 00 00 00 CC 13",
                Hex.format(LogBuilder.raw().checkpoint(4294967295L, 0).build()));
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
