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
        // Derivation (bytes and outcome are PROMPT.md section 11, Example 1):
        //   1. Header ok (R4). 12 records: BEGIN 1 @4, BEGIN 2 @13, PUT 1 a=1 @22, PUT 2 a=2 @36,
        //      PUT 2 b=x @50, COMMIT 2 @64, BEGIN 3 @73, PUT 3 b=y @82, COMMIT 1 @96, ABORT 3 @105,
        //      BEGIN 4 @114, DELETE 4 a @123 (N = 6, ends 134). Each: type 1-5 (valid, R7 allows
        //      1-7), N <= 1024, fits, correct checksum (e.g. PUT @22: s2 54 = 36, s1 160 = A0)
        //      -> intact (R11).
        //   2. p = 134 = L (R5): validLength = 134, tornTail = false.
        //   3. Phase 2: every record passes R13-R19b (at most 2 writes per txn, so R19a never
        //      fails). COMMIT 2 applies a=2, b=x; COMMIT 1 then sets a=1 (R22, commit order);
        //      ABORT 3 drops b=y (R23); txn 4 stays open, its DELETE is never applied.
        //      committed [2, 1], aborted [3], incomplete [4] (R25, R26).
        // Expected: OK state={"a"="1", "b"="x"} committed=[2, 1] aborted=[3] incomplete=[4] validLength=134 tornTail=false
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
        // Derivation (bytes and outcome are PROMPT.md section 11, Example 2):
        //   1. Header ok. BEGIN 1 @4 (ends 13), PUT 1 x=hello @13 (N = 13, ends 31, F8 A4),
        //      COMMIT 1 @31 (ends 40), BEGIN 2 @40 (ends 49), PUT 2 x=bye @49 (N = 11, ends 65,
        //      16 CC): all intact. L = 71.
        //   2. p = 65: R6 L-p = 6; R7 type 4; R8 N = 4; R9 65+5+4 = 74 > 71 -> torn tail at 65
        //      (R12): validLength = 65, tornTail = true.
        //   3. Phase 2 on the 5 intact records: txn 1 commits x=hello; txn 2 stays open, its PUT
        //      is not applied. committed [1], incomplete [2].
        // Expected: OK state={"x"="hello"} committed=[1] aborted=[] incomplete=[2] validLength=65 tornTail=true
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
        // Derivation (bytes and outcome are PROMPT.md section 11, Example 3):
        //   1. Header ok. BEGIN 1 @4 intact (ends 13). L = 36.
        //   2. p = 13: type 2, N = 9, ends 27 <= 36. Checksum of 02 00 09 00 00 00 01 01 6B 00 01 77:
        //      s1 2,2,11,11,11,11,12,13,120,120,121,240; s2 2,4,15,26,37,48,60,73,193,58,179,164
        //      -> A4 F0, stored A3 EF: wrong. 27 < 36 -> throw CHECKSUM_MISMATCH at 13 (R10).
        // Expected: ERR CHECKSUM_MISMATCH@13
        c.add(TestCase.of("P03", "example3_checksum_mismatch_middle", "R10", Hex.bytes(
                "57 41 4C 31"
              + " 01 00 04 00 00 00 01 1C 06"
              + " 02 00 09 00 00 00 01 01 6B 00 01 77 A3 EF"
              + " 04 00 04 00 00 00 01 31 09"),
                "ERR CHECKSUM_MISMATCH@13"));

        // P04 Example 4: savepoint and rollback. R5, R19a, R21, R22, R23a, R23b.
        // Derivation (bytes and outcome are PROMPT.md section 11, Example 4):
        //   1. Header ok (R4). 8 records: BEGIN 1 @4 (ends 13), PUT 1 a=1 @13 (N = 9, ends 27, 36 A0),
        //      SAVEPOINT 1 name 73 @27 (type 6, N = 5, ends 37; 06 00 05 00 00 00 01 73:
        //      s1 6,6,11,11,11,11,12,127; s2 6,12,23,34,45,56,68,195 -> C3 7F), PUT 1 b=2 @37
        //      (ends 51, 3B A2), DELETE 1 a @51 (N = 6, ends 62, AB 6C), ROLLBACK 1 name 73 @62
        //      (type 7, N = 5, ends 72, CB 80), PUT 1 c=3 @72 (ends 86, 40 A4), COMMIT 1 @86
        //      (ends 95, 31 09). Each: type 1-7, N <= 1024, fits, correct checksum -> intact (R11).
        //   2. p = 95 = L (R5): validLength = 95, tornTail = false.
        //   3. Phase 2: every record passes R13-R19b (SAVEPOINT/ROLLBACK N = 5, name 73 in 21..7E,
        //      txn 1 open, at most 2 writes in the list so R19a never fires, savepoint 73 exists at
        //      the ROLLBACK). writes(1) = [PUT a=1]; SAVEPOINT 73 -> position 1 (R23a);
        //      writes = [PUT a=1, PUT b=2, DELETE a]; ROLLBACK 73 keeps the first 1 write ->
        //      [PUT a=1] (R23b), txn 1 stays open; PUT c=3 -> [PUT a=1, PUT c=3]; COMMIT applies
        //      a=1 then c=3 (R22). committed [1], aborted [], incomplete [] (R25, R26).
        // Expected: OK state={"a"="1", "c"="3"} committed=[1] aborted=[] incomplete=[] validLength=95 tornTail=false
        c.add(TestCase.of("P04", "example4_savepoint_rollback", "R5,R19a,R21,R22,R23a,R23b", Hex.bytes(
                "57 41 4C 31"
              + " 01 00 04 00 00 00 01 1C 06"
              + " 02 00 09 00 00 00 01 01 61 00 01 31 36 A0"
              + " 06 00 05 00 00 00 01 73 C3 7F"
              + " 02 00 09 00 00 00 01 01 62 00 01 32 3B A2"
              + " 03 00 06 00 00 00 01 01 61 AB 6C"
              + " 07 00 05 00 00 00 01 73 CB 80"
              + " 02 00 09 00 00 00 01 01 63 00 01 33 40 A4"
              + " 04 00 04 00 00 00 01 31 09"),
                "OK state={\"a\"=\"1\", \"c\"=\"3\"} committed=[1] aborted=[] incomplete=[] validLength=95 tornTail=false"));

        return c;
    }
}
