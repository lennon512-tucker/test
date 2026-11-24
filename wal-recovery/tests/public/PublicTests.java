import java.util.ArrayList;
import java.util.List;

/**
 * The worked examples of task/PROMPT.md section 11. Inputs are the exact hex of the examples;
 * expected lines are literals worked out by hand (PROMPT.md "Expected result").
 */
public final class PublicTests {
    private PublicTests() {}

    public static List<TestCase> cases() {
        List<TestCase> c = new ArrayList<>();

        // P01 Example 1: interleaved transactions. R5, R22, R23, R26.
        c.add(TestCase.of("P01", "example1_interleaved_transactions", "R5,R22,R23,R26", Hex.bytes(
                "57 41 4C 31"
              + " 01 00 04 00 00 00 01 1C 06"
              + " 01 00 04 00 00 00 02 1D 07"
              + " 02 00 09 00 00 00 01 01 61 00 01 31 36 A0"
              + " 02 00 09 00 00 00 02 01 61 00 01 32 3D A2"
              + " 02 00 09 00 00 00 02 01 62 00 01 78 87 E9"
              + " 04 00 04 00 00 00 02 32 0A"
              + " 01 00 04 00 00 00 03 1E 08"
              + " 02 00 09 00 00 00 03 01 62 00 01 79 8E EB"
              + " 04 00 04 00 00 00 01 31 09"
              + " 05 00 04 00 00 00 03 3A 0C"
              + " 01 00 04 00 00 00 04 1F 09"
              + " 03 00 06 00 00 00 04 01 61 B4 6F"),
                "OK state={\"a\"=\"1\", \"b\"=\"x\"} committed=[2, 1] aborted=[3] incomplete=[4] validLength=134 tornTail=false"));

        // P02 Example 2: torn tail. R9, R12, R22, R26.
        c.add(TestCase.of("P02", "example2_torn_tail", "R9,R12,R22,R26", Hex.bytes(
                "57 41 4C 31"
              + " 01 00 04 00 00 00 01 1C 06"
              + " 02 00 0D 00 00 00 01 01 78 00 05 68 65 6C 6C 6F F8 A4"
              + " 04 00 04 00 00 00 01 31 09"
              + " 01 00 04 00 00 00 02 1D 07"
              + " 02 00 0B 00 00 00 02 01 78 00 03 62 79 65 16 CC"
              + " 04 00 04 00 00 00"),
                "OK state={\"x\"=\"hello\"} committed=[1] aborted=[] incomplete=[2] validLength=65 tornTail=true"));

        // P03 Example 3: checksum error in the middle. R10.
        c.add(TestCase.of("P03", "example3_checksum_mismatch_middle", "R10", Hex.bytes(
                "57 41 4C 31"
              + " 01 00 04 00 00 00 01 1C 06"
              + " 02 00 09 00 00 00 01 01 6B 00 01 77 A3 EF"
              + " 04 00 04 00 00 00 01 31 09"),
                "ERR CHECKSUM_MISMATCH@13"));

        return c;
    }
}
