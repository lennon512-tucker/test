import java.util.ArrayList;
import java.util.List;

/**
 * Hidden tests. Every expected line is a literal worked out by hand from task/PROMPT.md; the
 * derivation is written next to each test. Rule numbers (R1-R27, P1-P5) are those of PROMPT.md.
 *
 * Conventions in the derivations:
 *   - "rec X @p, size s, ends e" gives the offset of the type byte, the record size 5 + N and
 *     the end offset p + 5 + N.
 *   - Checksums are written "s2 s1" (stored order, PROMPT.md 5.4). Checksums of short records
 *     come with their s1/s2 trace or are taken from PROMPT.md section 11 (Example 1 or 2).
 *   - Phase 1 at each p runs R5, R6, R7, R8, R9, R10, R11 in that order; phase 2 runs R13-R19,
 *     then the action R20-R23.
 *   - LogBuilder.record()/begin()/put()/... write the true N and the correct checksum (checked
 *     against the PROMPT.md hex in runner/selftest.py). corruptLastChecksum() XORs both stored
 *     checksum bytes with 01, so the stored value always differs from the correct one (PLAN.md
 *     4.3: no 00<->FF byte change is used anywhere).
 *
 * Groups: A header (H01-H06), B framing and tail (H07-H20), C checksum details (H21-H24),
 * D payload structure (H25-H31), E field checks (H32-H40), F transactions (H41-H52),
 * G precedence and interactions (H53-H61), H API contract (H62-H63).
 */
public final class HiddenTests {
    private HiddenTests() {}

    public static List<TestCase> cases() {
        List<TestCase> c = new ArrayList<>();

        // =====================================================================================
        // Group A: header
        // =====================================================================================

        // H01 header_empty_log
        // Rules: R2
        // Derivation:
        //   1. log is not null (R1 does not apply). L = 0.
        //   2. R2: L = 0 -> the empty result with validLength = 0, tornTail = false.
        //      No phase runs.
        // Expected: OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=false
        c.add(TestCase.of("H01", "header_empty_log", "R2",
                LogBuilder.raw().build(),
                "OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=false"));

        // H02 header_only_log
        // Rules: R4, R5, R27; Not errors item 5
        // Derivation:
        //   1. Bytes 57 41 4C 31, L = 4. R2 (L = 0) and R3 (L = 1..3) do not apply.
        //   2. R4: L >= 4 and bytes 0-3 equal MAGIC -> phase 1 starts with p = 4.
        //   3. p = 4: R5: p = L -> phase 1 ends, validLength = 4, tornTail = false.
        //   4. Phase 2: no intact records. state {}, all lists empty (R24-R26).
        // Expected: OK state={} committed=[] aborted=[] incomplete=[] validLength=4 tornTail=false
        c.add(TestCase.of("H02", "header_only_log", "R4,R5,R27",
                LogBuilder.wal().build(),
                "OK state={} committed=[] aborted=[] incomplete=[] validLength=4 tornTail=false"));

        // H03 header_partial_magic_one_byte_is_torn
        // Rules: R3
        // Derivation:
        //   1. Bytes 57, L = 1. R2 does not apply.
        //   2. R3: L = 1; the 1 byte 57 equals MAGIC byte 0 (57) -> the empty result with
        //      validLength = 0, tornTail = true.
        // Expected: OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=true
        c.add(TestCase.of("H03", "header_partial_magic_one_byte_is_torn", "R3",
                LogBuilder.raw().bytes(0x57).build(),
                "OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=true"));

        // H04 header_partial_magic_two_bytes_is_torn
        // Rules: R3
        // Derivation:
        //   1. Bytes 57 41, L = 2. R2 does not apply.
        //   2. R3: L = 2; 57 41 equals the first 2 bytes of MAGIC (57 41) -> the empty result
        //      with validLength = 0, tornTail = true.
        // Expected: OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=true
        c.add(TestCase.of("H04", "header_partial_magic_two_bytes_is_torn", "R3",
                LogBuilder.raw().bytes(0x57, 0x41).build(),
                "OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=true"));

        // H05 header_three_bytes_wrong_third_is_bad_magic
        // Rules: R3, P1
        // Derivation:
        //   1. Bytes 57 41 58, L = 3. R2 does not apply.
        //   2. R3: L = 3; the first 3 bytes of MAGIC are 57 41 4C. Byte 2 is 58, not 4C, so the
        //      bytes differ -> throw BAD_MAGIC at 0.
        // Expected: ERR BAD_MAGIC@0
        c.add(TestCase.of("H05", "header_three_bytes_wrong_third_is_bad_magic", "R3,P1",
                LogBuilder.raw().bytes(0x57, 0x41, 0x58).build(),
                "ERR BAD_MAGIC@0"));

        // H06 header_wrong_magic_last_byte_with_valid_records
        // Rules: R4, P1
        // Derivation:
        //   1. Header bytes 57 41 4C 32 ("WAL2"), then BEGIN 1 @4 (01 00 04 00 00 00 01 1C 06,
        //      size 9, ends 13) and COMMIT 1 @13 (04 00 04 00 00 00 01 31 09, ends 22). L = 22.
        //   2. R2, R3 do not apply (L = 22). R4: byte 3 is 32, MAGIC byte 3 is 31 -> bytes 0-3
        //      are not MAGIC -> throw BAD_MAGIC at 0. P1: no record is read, although both
        //      records would be intact.
        // Expected: ERR BAD_MAGIC@0
        c.add(TestCase.of("H06", "header_wrong_magic_last_byte_with_valid_records", "R4,P1",
                LogBuilder.raw().bytes(0x57, 0x41, 0x4C, 0x32).begin(1).commit(1).build(),
                "ERR BAD_MAGIC@0"));

        // =====================================================================================
        // Group B: framing and tail
        // =====================================================================================

        // H07 tail_one_leftover_byte_after_commit
        // Rules: R6, R12, R22, R25; Not errors item 3 and 6
        // Derivation:
        //   1. Header ok (R4). BEGIN 1 @4, size 9, ends 13 (checksum 1C 06, PROMPT.md 5.4).
        //      COMMIT 1 @13, size 9, ends 22 (04 00 04 00 00 00 01; s1 4,4,8,8,8,8,9;
        //      s2 4,8,16,24,32,40,49 -> 31 09, correct). Then 1 byte 04 @22. L = 23.
        //   2. p = 4: R5 4 != 23; R6 L-p = 19; R7 type 1 ok; R8 N = 4; R9 4+5+4 = 13 <= 23;
        //      R10 checksum correct; R11 intact, p = 13.
        //   3. p = 13: R5 no; R6 L-p = 10; R7 type 4; R8 N = 4; R9 22 <= 23; R10 correct;
        //      R11 intact, p = 22.
        //   4. p = 22: R5 22 != 23; R6 L-p = 1 -> torn tail at 22 (R12): validLength = 22,
        //      tornTail = true. (The byte 04 looks like a COMMIT type but R6 comes first.)
        //   5. Phase 2: BEGIN 1 (R13 N = 4, R14 txn 1, R17 new id, R18 0 open) -> open (R20).
        //      COMMIT 1 (R13 N = 4, R14, R19 open) -> no writes, committed = [1] (R22).
        // Expected: OK state={} committed=[1] aborted=[] incomplete=[] validLength=22 tornTail=true
        c.add(TestCase.of("H07", "tail_one_leftover_byte_after_commit", "R6,R12,R22,R25",
                LogBuilder.wal().begin(1).commit(1).bytes(0x04).build(),
                "OK state={} committed=[1] aborted=[] incomplete=[] validLength=22 tornTail=true"));

        // H08 tail_two_leftover_bytes_unknown_type_is_torn
        // Rules: R6, R12, R26, P5
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, size 9, ends 13 (1C 06). Then bytes 09 00 @13. L = 15.
        //   2. p = 4: R6 L-p = 11; R7 type 1; R8 N = 4; R9 13 <= 15; R10 correct; intact,
        //      p = 13.
        //   3. p = 13: R5 13 != 15; R6 L-p = 2 -> torn tail at 13, whatever the bytes are; the
        //      type byte 09 is never checked by R7 (R6 comes first). validLength = 13,
        //      tornTail = true.
        //   4. Phase 2: BEGIN 1 opens txn 1; it is still open at the end -> incomplete = [1].
        // Expected: OK state={} committed=[] aborted=[] incomplete=[1] validLength=13 tornTail=true
        c.add(TestCase.of("H08", "tail_two_leftover_bytes_unknown_type_is_torn", "R6,R12,R26,P5",
                LogBuilder.wal().begin(1).bytes(0x09, 0x00).build(),
                "OK state={} committed=[] aborted=[] incomplete=[1] validLength=13 tornTail=true"));

        // H09 tail_three_leftover_bytes_unknown_type_is_error
        // Rules: R6, R7
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, size 9, ends 13 (1C 06). Then bytes 09 00 00 @13. L = 16.
        //   2. p = 4: intact as in H08 (R9 13 <= 16), p = 13.
        //   3. p = 13: R5 13 != 16; R6 L-p = 3, not 1 or 2; R7 type byte 09 is not 1..5 ->
        //      throw UNKNOWN_TYPE at 13.
        // Expected: ERR UNKNOWN_TYPE@13
        c.add(TestCase.of("H09", "tail_three_leftover_bytes_unknown_type_is_error", "R6,R7",
                LogBuilder.wal().begin(1).bytes(0x09, 0x00, 0x00).build(),
                "ERR UNKNOWN_TYPE@13"));

        // H10 frame_type_zero_in_middle
        // Rules: R7
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, size 9, ends 13 (1C 06).
        //      Record @13: type 00, N = 4, payload 00 00 00 01, size 9, ends 22, with its
        //      correct checksum (00 00 04 00 00 00 01; s1 0,0,4,4,4,4,5; s2 0,0,4,8,12,16,21
        //      -> 15 05). COMMIT 1 @22, size 9, ends 31 (31 09). L = 31.
        //   2. p = 4: intact (R9 13 <= 31, R10 correct), p = 13.
        //   3. p = 13: R5 no; R6 L-p = 18; R7 type 0 is not 1..5 -> throw UNKNOWN_TYPE at 13.
        //      (Frame size and checksum are fine; only the type is wrong.)
        // Expected: ERR UNKNOWN_TYPE@13
        c.add(TestCase.of("H10", "frame_type_zero_in_middle", "R7",
                LogBuilder.wal().begin(1).record(0, Hex.bytes("00 00 00 01")).commit(1).build(),
                "ERR UNKNOWN_TYPE@13"));

        // H11 frame_type_six_first_record
        // Rules: R7
        // Derivation:
        //   1. Header ok. Record @4: type 06, N = 4, payload 00 00 00 01, size 9, ends 13,
        //      correct checksum (06 00 04 00 00 00 01; s1 6,6,10,10,10,10,11;
        //      s2 6,12,22,32,42,52,63 -> 3F 0B). BEGIN 1 @13, size 9, ends 22 (1C 06). L = 22.
        //   2. p = 4: R5 no; R6 L-p = 18; R7 type 6 is not 1..5 (one above ABORT = 5) ->
        //      throw UNKNOWN_TYPE at 4.
        // Expected: ERR UNKNOWN_TYPE@4
        c.add(TestCase.of("H11", "frame_type_six_first_record", "R7",
                LogBuilder.wal().record(6, Hex.bytes("00 00 00 01")).begin(1).build(),
                "ERR UNKNOWN_TYPE@4"));

        // H12 tail_payload_cut_short_is_torn
        // Rules: R9, R12, R22, R26
        // Derivation:
        //   1. Header ok. Records (checksums from PROMPT.md Example 1):
        //      BEGIN 1 @4, size 9, ends 13 (1C 06).
        //      PUT 1 "a"="1" @13, N = 7+1+1 = 9, size 14, ends 27 (36 A0).
        //      COMMIT 1 @27, size 9, ends 36 (31 09).
        //      BEGIN 2 @36, size 9, ends 45 (1D 07).
        //      PUT 2 "b"="2" @45, N = 9, would end at 59; the last 5 bytes are dropped, so the
        //      log keeps 02 00 09 00 00 00 02 01 62 (type, N, 6 of 9 payload bytes). L = 54.
        //   2. p = 4, 13, 27, 36: each R6 (L-p >= 18), R7 type 1/2/4/1, R8 N <= 9, R9 end <= 45
        //      <= 54, R10 correct -> intact; p becomes 13, 27, 36, 45.
        //   3. p = 45: R5 no; R6 L-p = 9; R7 type 2; R8 N = 9; R9 45+5+9 = 59 > 54 -> torn tail
        //      at 45: validLength = 45, tornTail = true.
        //   4. Phase 2: BEGIN 1 opens 1. PUT 1 (R13: N = 9 >= 5, k = 1, 9 >= 8, v = 1,
        //      9 = 7+1+1; R14 txn 1; R15 key 61 ok; R16 value 31 ok; R19 open) -> write a=1.
        //      COMMIT 1 -> state {a=1}, committed [1]. BEGIN 2 (R17 new, R18 0 open) opens 2.
        //      End: incomplete = [2].
        // Expected: OK state={"a"="1"} committed=[1] aborted=[] incomplete=[2] validLength=45 tornTail=true
        c.add(TestCase.of("H12", "tail_payload_cut_short_is_torn", "R9,R12,R22,R26",
                LogBuilder.wal().begin(1).put(1, "a", "1").commit(1).begin(2).put(2, "b", "2")
                        .truncate(5).build(),
                "OK state={\"a\"=\"1\"} committed=[1] aborted=[] incomplete=[2] validLength=45 tornTail=true"));

        // H13 tail_last_record_bad_checksum_ends_at_L_is_torn
        // Rules: R10, R12, R25, P5
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, size 9, ends 13 (1C 06).
        //      PUT 1 "k"="v" @13, N = 9, size 14, ends 27 (02 00 09 00 00 00 01 01 6B 00 01 76;
        //      s1 2,2,11,11,11,11,12,13,120,120,121,239; s2 2,4,15,26,37,48,60,73,193,58,179,
        //      163 -> A3 EF).
        //      COMMIT 1 @27, N = 4, size 9, ends 36; correct checksum 31 09, stored 30 08
        //      (corruptLastChecksum). L = 36.
        //   2. p = 4, 13: intact, p = 27.
        //   3. p = 27: R5 no; R6 L-p = 9; R7 type 4; R8 N = 4; R9 36 <= 36; R10 stored 30 08 !=
        //      31 09, and 27+5+4 = 36 = L -> torn tail at 27: validLength = 27, tornTail = true.
        //   4. Phase 2: BEGIN 1 opens 1; PUT 1 k=v appended (R13 N = 9 = 7+1+1, key 6B, value
        //      76 ok). The COMMIT is in the torn tail, not processed (R25, P5).
        //      End: state {}, committed [], incomplete [1].
        // Expected: OK state={} committed=[] aborted=[] incomplete=[1] validLength=27 tornTail=true
        c.add(TestCase.of("H13", "tail_last_record_bad_checksum_ends_at_L_is_torn", "R10,R12,R25,P5",
                LogBuilder.wal().begin(1).put(1, "k", "v").commit(1).corruptLastChecksum().build(),
                "OK state={} committed=[] aborted=[] incomplete=[1] validLength=27 tornTail=true"));

        // H14 frame_bad_checksum_one_byte_before_L_is_mismatch
        // Rules: R10
        // Derivation:
        //   1. Same records as H13 (COMMIT 1 @27 with stored 30 08, correct 31 09, ends 36),
        //      plus 1 byte 00 @36. L = 37.
        //   2. p = 4, 13: intact, p = 27.
        //   3. p = 27: R6 L-p = 10; R7 type 4; R8 N = 4; R9 36 <= 37; R10 checksum wrong and
        //      27+5+4 = 36 < 37 -> throw CHECKSUM_MISMATCH at 27 ("even when only 1 or 2 bytes
        //      follow the record").
        // Expected: ERR CHECKSUM_MISMATCH@27
        c.add(TestCase.of("H14", "frame_bad_checksum_one_byte_before_L_is_mismatch", "R10",
                LogBuilder.wal().begin(1).put(1, "k", "v").commit(1).corruptLastChecksum()
                        .bytes(0x00).build(),
                "ERR CHECKSUM_MISMATCH@27"));

        // H15 frame_bad_checksum_in_middle
        // Rules: R10, P3
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). BEGIN 2 @13, ends 22 (1D 07).
        //      PUT 2 "x"="y" @22, N = 9, size 14, ends 36; bytes 02 00 09 00 00 00 02 01 78 00
        //      01 79: s1 2,2,11,11,11,11,13,14,134,134,135,1 (135+121 = 256 -> 1);
        //      s2 2,4,15,26,37,48,61,75,209,88,223,224 -> correct E0 01, stored E1 00
        //      (corruptLastChecksum).
        //      COMMIT 2 @36, ends 45 (32 0A). COMMIT 1 @45, ends 54 (31 09). L = 54.
        //   2. p = 4, 13: intact, p = 22.
        //   3. p = 22: R6 L-p = 32; R7 type 2; R8 N = 9; R9 36 <= 54; R10 wrong, 36 < 54 ->
        //      throw CHECKSUM_MISMATCH at 22. P3: the records at 36 and 45 are not looked at.
        // Expected: ERR CHECKSUM_MISMATCH@22
        c.add(TestCase.of("H15", "frame_bad_checksum_in_middle", "R10,P3",
                LogBuilder.wal().begin(1).begin(2).put(2, "x", "y").corruptLastChecksum()
                        .commit(2).commit(1).build(),
                "ERR CHECKSUM_MISMATCH@22"));

        // H16 frame_length_1025_is_too_large
        // Rules: R8
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      PUT 1 "k" = 1017 x "a" @13: N = 7+1+1017 = 1025 (N bytes 04 01:
        //      4*256+1 = 1025), size 1030, ends 1043, correct checksum (LogBuilder.record).
        //      COMMIT 1 @1043, ends 1052 (31 09). L = 1052.
        //   2. p = 4: intact, p = 13.
        //   3. p = 13: R5 no; R6 L-p = 1039; R7 type 2; R8 N = 1025 > MAX_PAYLOAD 1024 ->
        //      throw RECORD_TOO_LARGE at 13. (The record fits and its checksum is right, so
        //      only R8 can reject it.)
        // Expected: ERR RECORD_TOO_LARGE@13
        c.add(TestCase.of("H16", "frame_length_1025_is_too_large", "R8",
                LogBuilder.wal().begin(1).put(1, "k", "a".repeat(1017)).commit(1).build(),
                "ERR RECORD_TOO_LARGE@13"));

        // H17 frame_length_1024_is_allowed
        // Rules: R8, R11, R13, R21, R22, Not errors item 8
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      PUT 1 "k" = 1016 x "a" @13: N = 7+1+1016 = 1024 (N bytes 04 00), v bytes 03 F8
        //      (3*256+248 = 1016), size 1029, ends 1042. Correct checksum s2 s1 = 13 E9
        //      (s1 by hand: byte sum = 2+4+0+0+0+0+1+1+107+3+248 + 1016*97 = 366 + 98552 =
        //      98918; 98918 - 387*255 = 98918 - 98685 = 233 = E9; s2 from the arithmetic
        //      script work/scratch/p5_fletcher.py).
        //      PUT 1 "k"="ok" @1042: N = 7+1+2 = 10, size 15, ends 1057 (02 00 0A 00 00 00 01
        //      01 6B 00 02 6F 6B; s1 2,2,12,12,12,12,13,14,121,121,123,234,86;
        //      s2 2,4,16,28,40,52,65,79,200,66,189,168,254 -> FE 56).
        //      COMMIT 1 @1057, ends 1066 (31 09). L = 1066.
        //   2. Phase 1: p = 4 intact; p = 13: R7 type 2; R8 N = 1024 is not > 1024; R9 1042 <=
        //      1066; R10 correct -> intact; p = 1042 intact; p = 1057 intact; p = 1066 = L (R5):
        //      validLength = 1066, tornTail = false.
        //   3. Phase 2: BEGIN 1 opens. PUT @13: R13 N = 1024 >= 5, k = 1, 1024 >= 8, v = 1016,
        //      1024 = 7+1+1016; R15 key 6B; R16 all value bytes 61 in 20..7E; R19 open -> write
        //      k=<1016 a>. PUT @1042: R13 10 = 7+1+2; key 6B; value 6F 6B ok -> write k=ok.
        //      COMMIT: apply in order, k = 1016 x a, then k = "ok" -> state {k=ok}; committed [1].
        // Expected: OK state={"k"="ok"} committed=[1] aborted=[] incomplete=[] validLength=1066 tornTail=false
        c.add(TestCase.of("H17", "frame_length_1024_is_allowed", "R8,R11,R13,R21,R22",
                LogBuilder.wal().begin(1).put(1, "k", "a".repeat(1016)).put(1, "k", "ok").commit(1)
                        .build(),
                "OK state={\"k\"=\"ok\"} committed=[1] aborted=[] incomplete=[] validLength=1066 tornTail=false"));

        // H18 frame_length_0x8001_is_too_large_unsigned
        // Rules: R8 (N is unsigned)
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      Record @13: type 02, N bytes 80 01 = 128*256+1 = 32769, payload 32769 bytes 00,
        //      correct checksum (LogBuilder.record), size 32774, ends 32787.
        //      COMMIT 1 @32787, ends 32796 (31 09). L = 32796 (<= 65536).
        //   2. p = 4: intact, p = 13.
        //   3. p = 13: R6 L-p = 32783; R7 type 2; R8 N = 32769 > 1024 -> throw RECORD_TOO_LARGE
        //      at 13. (Read as a signed 16-bit value N would be -32767; R8 says N is unsigned.
        //      The whole record is present with a correct checksum, so only R8 rejects it.)
        // Expected: ERR RECORD_TOO_LARGE@13
        c.add(TestCase.of("H18", "frame_length_0x8001_is_too_large_unsigned", "R8",
                LogBuilder.wal().begin(1).record(2, new byte[0x8001]).commit(1).build(),
                "ERR RECORD_TOO_LARGE@13"));

        // H19 frame_cut_short_length_2000_too_large_beats_torn
        // Rules: R8, R9
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). Then 9 bytes @13: 02 07 D0 00 00 00 01
        //      01 6B (type 2, N bytes 07 D0 = 7*256+208 = 2000, then 6 payload bytes). L = 22.
        //   2. p = 4: intact, p = 13.
        //   3. p = 13: R5 no; R6 L-p = 9; R7 type 2; R8 N = 2000 > 1024 -> throw
        //      RECORD_TOO_LARGE at 13. R8 comes before R9, so the torn tail that R9 would find
        //      (13+5+2000 = 2018 > 22) is never reached.
        // Expected: ERR RECORD_TOO_LARGE@13
        c.add(TestCase.of("H19", "frame_cut_short_length_2000_too_large_beats_torn", "R8,R9",
                LogBuilder.wal().begin(1).bytes(0x02, 0x07, 0xD0, 0x00, 0x00, 0x00, 0x01, 0x01, 0x6B)
                        .build(),
                "ERR RECORD_TOO_LARGE@13"));

        // H20 frame_last_record_unknown_type_bad_checksum_is_error
        // Rules: R7, R10
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). Record @13: type 07, N = 4, payload
        //      00 00 00 01, stored checksum 00 00, size 9, ends 22. Its correct checksum would be
        //      46 0C (07 00 04 00 00 00 01; s1 7,7,11,11,11,11,12; s2 7,14,25,36,47,58,70), so
        //      the stored one is wrong. L = 22.
        //   2. p = 4: intact, p = 13.
        //   3. p = 13: R5 no; R6 L-p = 9; R7 type 7 is not 1..5 -> throw UNKNOWN_TYPE at 13.
        //      R7 comes before R10, so the torn tail that R10 would give (ends at 22 = L) is
        //      never reached.
        // Expected: ERR UNKNOWN_TYPE@13
        c.add(TestCase.of("H20", "frame_last_record_unknown_type_bad_checksum_is_error", "R7,R10",
                LogBuilder.wal().begin(1).frame(7, 4, Hex.bytes("00 00 00 01"), 0x0000).build(),
                "ERR UNKNOWN_TYPE@13"));

        // =====================================================================================
        // Group C: checksum details
        // =====================================================================================

        // H21 checksum_over_payload_only_is_rejected
        // Rules: R10 (checksum covers type and N, PROMPT.md 5.4)
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      Record @13: type 02, N = 9, payload 00 00 00 01 01 61 00 01 31 (PUT 1 "a"="1"),
        //      size 14, ends 27. Stored checksum C3 95 = Fletcher-16 of the payload alone:
        //      s1 0,0,0,1,2,99,99,100,149 (149 = 95); s2 0,0,0,1,3,102,201,46,195 (195 = C3).
        //      The correct checksum over type, N and payload (02 00 09 + payload) is 36 A0
        //      (PROMPT.md Example 1, record @22). C3 95 != 36 A0 -> wrong.
        //      COMMIT 1 @27, ends 36 (31 09). L = 36.
        //   2. p = 4: intact, p = 13.
        //   3. p = 13: R6 L-p = 23; R7 type 2; R8 N = 9; R9 27 <= 36; R10 wrong and 27 < 36 ->
        //      throw CHECKSUM_MISMATCH at 13.
        // Expected: ERR CHECKSUM_MISMATCH@13
        c.add(TestCase.of("H21", "checksum_over_payload_only_is_rejected", "R10",
                LogBuilder.wal().begin(1)
                        .frame(2, 9, Hex.bytes("00 00 00 01 01 61 00 01 31"), 0xC395)
                        .commit(1).build(),
                "ERR CHECKSUM_MISMATCH@13"));

        // H22 checksum_txn_ff_bytes_unsigned
        // Rules: R10, R11, R23, R25 (bytes unsigned, PROMPT.md 4 and 5.4)
        // Derivation:
        //   1. Header ok. Records for txn FF FF FF FF = 4294967295:
        //      BEGIN @4, size 9, ends 13: 01 00 04 FF FF FF FF; s1 1,1,5,5,5,5,5 (5+255 = 260
        //      -> 5); s2 1,2,7,12,17,22,27 -> 1B 05.
        //      PUT "k"="v" @13, N = 9, size 14, ends 27: 02 00 09 FF FF FF FF 01 6B 00 01 76;
        //      s1 2,2,11,11,11,11,11,12,119,119,120,238; s2 2,4,15,26,37,48,59,71,190,54,174,157
        //      -> 9D EE.
        //      ABORT @27, size 9, ends 36: 05 00 04 FF FF FF FF; s1 5,5,9,9,9,9,9;
        //      s2 5,10,19,28,37,46,55 -> 37 09. L = 36.
        //      (Adding the FF bytes as signed -1 would give, for the BEGIN, s1 1,1,5,4,3,2,1 and
        //      s2 1,2,7,11,14,16,17 -> 11 01, not 1B 05.)
        //   2. Phase 1: p = 4, 13, 27 all intact (R9 ends 13, 27, 36 <= 36; R10 correct);
        //      p = 36 = L (R5): validLength = 36, tornTail = false.
        //   3. Phase 2: BEGIN (R13 N = 4; R14 txn 4294967295 != 0; R17 new; R18 0 open) opens.
        //      PUT (R13 9 = 7+1+1; R15 key 6B; R16 value 76; R19 open) -> write appended.
        //      ABORT (R13, R14, R19 open) -> writes dropped, aborted = [4294967295] (R23).
        //   4. Result: state {}, ids as non-negative long (R25 / section 7.4).
        // Expected: OK state={} committed=[] aborted=[4294967295] incomplete=[] validLength=36 tornTail=false
        c.add(TestCase.of("H22", "checksum_txn_ff_bytes_unsigned", "R10,R11,R23,R25",
                LogBuilder.wal().begin(4294967295L).put(4294967295L, "k", "v").abort(4294967295L)
                        .build(),
                "OK state={} committed=[] aborted=[4294967295] incomplete=[] validLength=36 tornTail=false"));

        // H23 checksum_length_byte_c8_unsigned
        // Rules: R8, R10, R11, R13, R22 (bytes unsigned, PROMPT.md 4)
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      PUT 1 "v" = 192 x "x" @13: N = 7+1+192 = 200 (N bytes 00 C8; C8 = 200), v bytes
        //      00 C0 (C0 = 192), size 205, ends 218. Correct checksum s2 s1 = 64 5E (s1 by hand:
        //      byte sum = 2+0+200+0+0+0+1+1+118+0+192 + 192*120 = 514 + 23040 = 23554;
        //      23554 - 92*255 = 23554 - 23460 = 94 = 5E; s2 from work/scratch/p5_fletcher.py).
        //      DELETE 1 "v" @218: N = 5+1 = 6, size 11, ends 229 (03 00 06 00 00 00 01 01 76;
        //      s1 3,3,9,9,9,9,10,11,129; s2 3,6,15,24,33,42,52,63,192 -> C0 81).
        //      PUT 1 "n"="200" @229: N = 7+1+3 = 11, size 16, ends 245 (02 00 0B 00 00 00 01 01
        //      6E 00 03 32 30 30; s1 2,2,13,13,13,13,14,15,125,125,128,178,226,19;
        //      s2 2,4,17,30,43,56,70,85,210,80,208,131,102,121 -> 79 13).
        //      COMMIT 1 @245, ends 254 (31 09). L = 254.
        //   2. Phase 1: p = 4 intact; p = 13: R7 type 2; R8 N = 200 <= 1024; R9 218 <= 254;
        //      R10 correct -> intact; p = 218, 229, 245 intact; p = 254 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. PUT @13: R13 200 >= 5, k = 1, 200 >= 8, v = 192,
        //      200 = 7+1+192; R15 key 76; R16 value bytes 78 ok -> write v=<192 x>.
        //      DELETE @218: R13 6 >= 5, k = 1, 6 = 5+1; R15 key 76 -> write del v.
        //      PUT @229: R13 11 = 7+1+3; key 6E; value 32 30 30 -> write n=200.
        //      COMMIT: v = 192 x "x", then v removed, then n = "200" -> state {n=200};
        //      committed [1].
        //   (Read as signed bytes, C8 would be -56 and C0 -64; then N, v and the checksum
        //   would all come out wrong.)
        // Expected: OK state={"n"="200"} committed=[1] aborted=[] incomplete=[] validLength=254 tornTail=false
        c.add(TestCase.of("H23", "checksum_length_byte_c8_unsigned", "R8,R10,R11,R13,R22",
                LogBuilder.wal().begin(1).put(1, "v", "x".repeat(192)).del(1, "v").put(1, "n", "200")
                        .commit(1).build(),
                "OK state={\"n\"=\"200\"} committed=[1] aborted=[] incomplete=[] validLength=254 tornTail=false"));

        // H24 checksum_s1_s2_swapped_is_rejected
        // Rules: R10 (stored order s2 then s1, PROMPT.md 5.4)
        // Derivation:
        //   1. Header ok. Record @4: BEGIN 1 (01 00 04 00 00 00 01), size 9, ends 13, stored
        //      checksum 06 1C, i.e. s1 then s2. Correct stored order is s2 s1 = 1C 06 (PROMPT.md
        //      5.4 check value). Byte 11 is 06, not s2 = 1C -> wrong.
        //      BEGIN 2 @13, ends 22 (1D 07). L = 22.
        //   2. p = 4: R6 L-p = 18; R7 type 1; R8 N = 4; R9 13 <= 22; R10 wrong and 13 < 22 ->
        //      throw CHECKSUM_MISMATCH at 4.
        // Expected: ERR CHECKSUM_MISMATCH@4
        c.add(TestCase.of("H24", "checksum_s1_s2_swapped_is_rejected", "R10",
                LogBuilder.wal().frame(1, 4, Hex.bytes("00 00 00 01"), 0x061C).begin(2).build(),
                "ERR CHECKSUM_MISMATCH@4"));

        // =====================================================================================
        // Checksum table for groups D-H (P6). Every record below is written by LogBuilder with
        // its true N and correct checksum unless the test says otherwise. Each line: the 3 + N
        // covered bytes (type, N, payload), the s1 and s2 traces, and the stored bytes s2 s1.
        // Records already traced in H01-H24 or PROMPT.md: BEGIN 1 1C 06, BEGIN 2 1D 07,
        // COMMIT 1 31 09, COMMIT 2 32 0A, ABORT 3 3A 0C, BEGIN FFFFFFFF 1B 05,
        // PUT 1 a=1 36 A0, PUT 2 a=2 3D A2, PUT 1 k=v A3 EF.
        //   BEGIN 0      01 00 04 00 00 00 00  s1 1,1,5,5,5,5,5   s2 1,2,7,12,17,22,27 -> 1B 05
        //   BEGIN 3      01 00 04 00 00 00 03  s1 1,1,5,5,5,5,8   s2 1,2,7,12,17,22,30 -> 1E 08
        //   BEGIN 4      01 00 04 00 00 00 04  s1 1,1,5,5,5,5,9   s2 1,2,7,12,17,22,31 -> 1F 09
        //   BEGIN 5      01 00 04 00 00 00 05  s1 1,1,5,5,5,5,10  s2 1,2,7,12,17,22,32 -> 20 0A
        //   BEGIN 6      01 00 04 00 00 00 06  s1 1,1,5,5,5,5,11  s2 1,2,7,12,17,22,33 -> 21 0B
        //   BEGIN 7      01 00 04 00 00 00 07  s1 1,1,5,5,5,5,12  s2 1,2,7,12,17,22,34 -> 22 0C
        //   BEGIN 200    01 00 04 00 00 00 C8  s1 1,1,5,5,5,5,205 s2 1,2,7,12,17,22,227 -> E3 CD
        //   BEGIN 2147483647 01 00 04 7F FF FF FF  s1 1,1,5,132,132,132,132
        //                                          s2 1,2,7,139,16,148,25 -> 19 84
        //   BEGIN 2147483648 01 00 04 80 00 00 00  s1 1,1,5,133,133,133,133
        //                                          s2 1,2,7,140,18,151,29 -> 1D 85
        //   COMMIT 0     04 00 04 00 00 00 00  s1 4,4,8,8,8,8,8   s2 4,8,16,24,32,40,48 -> 30 08
        //   COMMIT 3     04 00 04 00 00 00 03  s1 4,4,8,8,8,8,11  s2 4,8,16,24,32,40,51 -> 33 0B
        //   COMMIT 7     04 00 04 00 00 00 07  s1 4,4,8,8,8,8,15  s2 4,8,16,24,32,40,55 -> 37 0F
        //   COMMIT FFFFFFFF 04 00 04 FF FF FF FF  s1 4,4,8,8,8,8,8 s2 4,8,16,24,32,40,48 -> 30 08
        //   ABORT 2      05 00 04 00 00 00 02  s1 5,5,9,9,9,9,11  s2 5,10,19,28,37,46,57 -> 39 0B
        //   ABORT 5      05 00 04 00 00 00 05  s1 5,5,9,9,9,9,14  s2 5,10,19,28,37,46,60 -> 3C 0E
        //   BEGIN N=3    01 00 03 00 00 02     s1 1,1,4,4,4,6     s2 1,2,6,10,14,20 -> 14 06
        //   BEGIN N=5    01 00 05 00 00 00 01 00  s1 1,1,6,6,6,6,7,7  s2 1,2,8,14,20,26,33,40 -> 28 07
        //   PUT N=8 k=5  02 00 08 00 00 00 01 05 61 62 63  s1 2,2,10,10,10,10,11,16,113,211,55
        //                s2 2,4,14,24,34,44,55,71,184,140,195 -> C3 37
        //   PUT N=7 k=1  02 00 07 00 00 00 01 01 61 00  s1 2,2,9,9,9,9,10,11,108,108
        //                s2 2,4,13,22,31,40,50,61,169,22 -> 16 6C
        //   PUT N=10 +1  02 00 0A 00 00 00 01 01 61 00 01 31 32
        //                s1 2,2,12,12,12,12,13,14,111,111,112,161,211
        //                s2 2,4,16,28,40,52,65,79,190,46,158,64,20 -> 14 D3
        //   DELETE N=4   03 00 04 00 00 00 01  s1 3,3,7,7,7,7,8   s2 3,6,13,20,27,34,42 -> 2A 08
        //   COMMIT N=0   04 00 00              s1 4,4,4           s2 4,8,12 -> 0C 04
        //   PUT 1 ""="1" 02 00 08 00 00 00 01 00 00 01 31  s1 2,2,10,10,10,10,11,11,11,12,61
        //                s2 2,4,14,24,34,44,55,66,77,89,150 -> 96 3D
        //   PUT 1 "a b"="1" 02 00 0B 00 00 00 01 03 61 20 62 00 01 31
        //                s1 2,2,13,13,13,13,14,17,114,146,244,244,245,39
        //                s2 2,4,17,30,43,56,70,87,201,92,81,70,60,99 -> 63 27
        //   PUT 1 "v"=" ~" 02 00 0A 00 00 00 01 01 76 00 02 20 7E
        //                s1 2,2,12,12,12,12,13,14,132,132,134,166,37
        //                s2 2,4,16,28,40,52,65,79,211,88,222,133,170 -> AA 25
        //   PUT 1 "v"=61 1F  02 00 0A 00 00 00 01 01 76 00 02 61 1F
        //                s1 2,2,12,12,12,12,13,14,132,132,134,231,7
        //                s2 2,4,16,28,40,52,65,79,211,88,222,198,205 -> CD 07
        //   PUT 1 "b"=78 7F  02 00 0A 00 00 00 01 01 62 00 02 78 7F
        //                s1 2,2,12,12,12,12,13,14,112,112,114,234,106
        //                s2 2,4,16,28,40,52,65,79,191,48,162,141,247 -> F7 6A
        //   DELETE 1 61 7F  03 00 07 00 00 00 01 02 61 7F  s1 3,3,10,10,10,10,11,13,110,237
        //                s2 3,6,16,26,36,46,57,70,180,162 -> A2 ED
        //   PUT 3 k=b    02 00 09 00 00 00 03 01 6B 00 01 62  s1 2,2,11,11,11,11,14,15,122,122,123,221
        //                s2 2,4,15,26,37,48,62,77,199,66,189,155 -> 9B DD
        //   PUT 7 k=a    02 00 09 00 00 00 07 01 6B 00 01 61  s1 2,2,11,11,11,11,18,19,126,126,127,224
        //                s2 2,4,15,26,37,48,66,85,211,82,209,178 -> B2 E0
        //   PUT 3 m=1    02 00 09 00 00 00 03 01 6D 00 01 31  s1 2,2,11,11,11,11,14,15,124,124,125,174
        //                s2 2,4,15,26,37,48,62,77,201,70,195,114 -> 72 AE
        //   PUT 2 b=2    02 00 09 00 00 00 02 01 62 00 01 32  s1 2,2,11,11,11,11,13,14,112,112,113,163
        //                s2 2,4,15,26,37,48,61,75,187,44,157,65 -> 41 A3
        //   PUT 1 x=1    02 00 09 00 00 00 01 01 78 00 01 31  s1 2,2,11,11,11,11,12,13,133,133,134,183
        //                s2 2,4,15,26,37,48,60,73,206,84,218,146 -> 92 B7
        //   PUT 1 b=2    02 00 09 00 00 00 01 01 62 00 01 32  s1 2,2,11,11,11,11,12,13,111,111,112,162
        //                s2 2,4,15,26,37,48,60,73,184,40,152,59 -> 3B A2
        //   PUT 1 b=""   02 00 08 00 00 00 01 01 62 00 00  s1 2,2,10,10,10,10,11,12,110,110,110
        //                s2 2,4,14,24,34,44,55,67,177,32,142 -> 8E 6E
        //   PUT FFFFFFFF max=u32  02 00 0D FF FF FF FF 03 6D 61 78 00 03 75 33 32
        //                s1 2,2,15,15,15,15,15,18,127,224,89,89,92,209,5,55
        //                s2 2,4,19,34,49,64,79,97,224,193,27,116,208,162,167,222 -> DE 37
        //   PUT 0 "a b"="1" 02 00 0B 00 00 00 00 03 61 20 62 00 01 31
        //                s1 2,2,13,13,13,13,13,16,113,145,243,243,244,38
        //                s2 2,4,17,30,43,56,69,85,198,88,76,64,53,91 -> 5B 26
        //   PUT 2 E9="1" 02 00 09 00 00 00 02 01 E9 00 01 31  s1 2,2,11,11,11,11,13,14,247,247,248,42
        //                s2 2,4,15,26,37,48,61,75,67,59,52,94 -> 5E 2A
        //   PUT 1 "v"=7F 02 00 09 00 00 00 01 01 76 00 01 7F  s1 2,2,11,11,11,11,12,13,131,131,132,4
        //                s2 2,4,15,26,37,48,60,73,204,80,212,216 -> D8 04
        //   PUT FFFFFFFF k="v x"  02 00 0B FF FF FF FF 01 6B 00 03 76 20 78
        //                s1 2,2,13,13,13,13,13,14,121,121,124,242,19,139
        //                s2 2,4,17,30,43,56,69,83,204,70,194,181,200,84 -> 54 8B
        //   PUT 2 a=""   02 00 08 00 00 00 02 01 61 00 00  s1 2,2,10,10,10,10,12,13,110,110,110
        //                s2 2,4,14,24,34,44,56,69,179,34,144 -> 90 6E
        //   PUT 3 z=~    02 00 09 00 00 00 03 01 7A 00 01 7E  s1 2,2,11,11,11,11,14,15,137,137,138,9
        //                s2 2,4,15,26,37,48,62,77,214,96,234,243 -> F3 09
        //   PUT 0 "a b"=01  02 00 0B 00 00 00 00 03 61 20 62 00 01 01
        //                s1 2,2,13,13,13,13,13,16,113,145,243,243,244,245
        //                s2 2,4,17,30,43,56,69,85,198,88,76,64,53,43 -> 2B F5
        //   DELETE 2 x   03 00 06 00 00 00 02 01 78  s1 3,3,9,9,9,9,11,12,132
        //                s2 3,6,15,24,33,42,53,65,197 -> C5 84
        //   DELETE 2 y   03 00 06 00 00 00 02 01 79  s1 3,3,9,9,9,9,11,12,133
        //                s2 3,6,15,24,33,42,53,65,198 -> C6 85
        //   DELETE 1 a   03 00 06 00 00 00 01 01 61  s1 3,3,9,9,9,9,10,11,108
        //                s2 3,6,15,24,33,42,52,63,171 -> AB 6C
        //   DELETE 2 k   03 00 06 00 00 00 02 01 6B  s1 3,3,9,9,9,9,11,12,119
        //                s2 3,6,15,24,33,42,53,65,184 -> B8 77
        // The traces were typed from work/scratch/p6_fletcher.py (Fletcher-16 arithmetic only).
        // A correct checksum only lets a record pass R10; none of the expected lines below
        // depends on the checksum value itself, except where a test corrupts one.
        // =====================================================================================

        // =====================================================================================
        // Group D: payload structure (R13, phase 2)
        // =====================================================================================

        // H25 payload_begin_n3_is_malformed
        // Rules: R11, R13
        // Derivation:
        //   1. Header ok (R4). BEGIN 1 @4, size 9, ends 13 (1C 06).
        //      Record @13: type 01, N = 3, payload 00 00 02, size 5+3 = 8, ends 21, correct
        //      checksum 14 06 (table). COMMIT 1 @21, size 9, ends 30 (31 09). L = 30.
        //   2. Phase 1: p = 4: R6 L-p = 26; R7 type 1; R8 N = 4; R9 13 <= 30; R10 correct ->
        //      intact, p = 13. p = 13: R6 17; R7 type 1; R8 N = 3 <= 1024; R9 21 <= 30;
        //      R10 correct -> intact (phase 1 does not look at the payload layout), p = 21.
        //      p = 21: intact, p = 30 = L (R5): validLength = 30, tornTail = false.
        //   3. Phase 2: BEGIN @4: R13 N = 4; R14 txn 1; R17 new; R18 0 open -> opens 1.
        //      BEGIN @13: R13 BEGIN needs N = 4, N = 3 -> throw MALFORMED_PAYLOAD at 13.
        // Expected: ERR MALFORMED_PAYLOAD@13
        c.add(TestCase.of("H25", "payload_begin_n3_is_malformed", "R11,R13",
                LogBuilder.wal().begin(1).record(1, Hex.bytes("00 00 02")).commit(1).build(),
                "ERR MALFORMED_PAYLOAD@13"));

        // H26 payload_begin_n5_is_malformed
        // Rules: R11, R13
        // Derivation:
        //   1. Header ok. Record @4: type 01, N = 5, payload 00 00 00 01 00, size 10, ends 14,
        //      correct checksum 28 07 (table). COMMIT 1 @14, size 9, ends 23 (31 09). L = 23.
        //   2. Phase 1: p = 4: R6 19; R7 type 1; R8 N = 5; R9 14 <= 23; R10 correct -> intact,
        //      p = 14. p = 14: intact, p = 23 = L (R5).
        //   3. Phase 2: BEGIN @4: R13 N = 5 != 4 -> throw MALFORMED_PAYLOAD at 4.
        //      (Reading only the first 4 payload bytes, txn 1, and ignoring the extra byte
        //      would wrongly give OK with committed = [1].)
        // Expected: ERR MALFORMED_PAYLOAD@4
        c.add(TestCase.of("H26", "payload_begin_n5_is_malformed", "R11,R13",
                LogBuilder.wal().record(1, Hex.bytes("00 00 00 01 00")).commit(1).build(),
                "ERR MALFORMED_PAYLOAD@4"));

        // H27 payload_put_key_past_payload_end_is_malformed
        // Rules: R13
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      Record @13: type 02, N = 8, payload 00 00 00 01 05 61 62 63, size 13, ends 26,
        //      correct checksum C3 37 (table). COMMIT 1 @26, ends 35 (31 09). L = 35.
        //   2. Phase 1: p = 4 intact; p = 13: R6 22; R7 type 2; R8 N = 8; R9 26 <= 35;
        //      R10 correct -> intact; p = 26 intact; p = 35 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. PUT @13: R13 N = 8 >= 5 ok; k = payload byte 4 = 05 = 5;
        //      N >= 7 + k = 12? 8 < 12 -> throw MALFORMED_PAYLOAD at 13. (The key would be
        //      payload bytes 5 to 9, but the payload ends at byte 7.)
        // Expected: ERR MALFORMED_PAYLOAD@13
        c.add(TestCase.of("H27", "payload_put_key_past_payload_end_is_malformed", "R13",
                LogBuilder.wal().begin(1).record(2, Hex.bytes("00 00 00 01 05 61 62 63")).commit(1)
                        .build(),
                "ERR MALFORMED_PAYLOAD@13"));

        // H28 payload_put_no_room_for_value_length_is_malformed
        // Rules: R13 (boundary of N >= 7 + k)
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      Record @13: type 02, N = 7, payload 00 00 00 01 01 61 00, size 12, ends 25,
        //      correct checksum 16 6C (table). COMMIT 1 @25, ends 34 (31 09). L = 34.
        //   2. Phase 1: p = 4 intact; p = 13: R8 N = 7; R9 25 <= 34; R10 correct -> intact;
        //      p = 25 intact; p = 34 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. PUT @13: R13 N = 7 >= 5; k = 1; N >= 7 + 1 = 8? 7 < 8
        //      -> throw MALFORMED_PAYLOAD at 13. (Only the first v byte, 00, is inside the
        //      payload; the next byte, @23, is the stored checksum 16.)
        // Expected: ERR MALFORMED_PAYLOAD@13
        c.add(TestCase.of("H28", "payload_put_no_room_for_value_length_is_malformed", "R13",
                LogBuilder.wal().begin(1).record(2, Hex.bytes("00 00 00 01 01 61 00")).commit(1)
                        .build(),
                "ERR MALFORMED_PAYLOAD@13"));

        // H29 payload_put_one_extra_byte_is_malformed
        // Rules: R13 (N = 7 + k + v exactly)
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      Record @13: type 02, N = 10, payload 00 00 00 01 01 61 00 01 31 32 (PUT 1 "a"="1"
        //      plus one byte 32), size 15, ends 28, correct checksum 14 D3 (table).
        //      COMMIT 1 @28, ends 37 (31 09). L = 37.
        //   2. Phase 1: p = 4 intact; p = 13: R8 N = 10; R9 28 <= 37; R10 correct -> intact;
        //      p = 28 intact; p = 37 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. PUT @13: R13 10 >= 5; k = 1; 10 >= 8; v = u16 at payload
        //      byte 6 = 00 01 = 1; N = 7 + 1 + 1 = 9? 10 != 9 -> throw MALFORMED_PAYLOAD at 13.
        // Expected: ERR MALFORMED_PAYLOAD@13
        c.add(TestCase.of("H29", "payload_put_one_extra_byte_is_malformed", "R13",
                LogBuilder.wal().begin(1).record(2, Hex.bytes("00 00 00 01 01 61 00 01 31 32"))
                        .commit(1).build(),
                "ERR MALFORMED_PAYLOAD@13"));

        // H30 payload_delete_n4_is_malformed
        // Rules: R13
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      Record @13: type 03, N = 4, payload 00 00 00 01 (txn only, no k), size 9,
        //      ends 22, correct checksum 2A 08 (table). COMMIT 1 @22, ends 31 (31 09). L = 31.
        //   2. Phase 1: p = 4 intact; p = 13: R7 type 3; R8 N = 4; R9 22 <= 31; R10 correct ->
        //      intact; p = 22 intact; p = 31 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. DELETE @13: R13 DELETE needs N >= 5; N = 4 -> throw
        //      MALFORMED_PAYLOAD at 13.
        // Expected: ERR MALFORMED_PAYLOAD@13
        c.add(TestCase.of("H30", "payload_delete_n4_is_malformed", "R13",
                LogBuilder.wal().begin(1).record(3, Hex.bytes("00 00 00 01")).commit(1).build(),
                "ERR MALFORMED_PAYLOAD@13"));

        // H31 payload_commit_n0_is_malformed
        // Rules: R13
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      Record @13: type 04, N = 0, no payload, size 5, ends 18, correct checksum 0C 04
        //      (table). BEGIN 2 @18, ends 27 (1D 07). L = 27.
        //   2. Phase 1: p = 4 intact; p = 13: R6 14; R7 type 4; R8 N = 0; R9 18 <= 27;
        //      R10 correct -> intact; p = 18 intact; p = 27 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. COMMIT @13: R13 N = 0 != 4 -> throw MALFORMED_PAYLOAD
        //      at 13.
        // Expected: ERR MALFORMED_PAYLOAD@13
        c.add(TestCase.of("H31", "payload_commit_n0_is_malformed", "R13",
                LogBuilder.wal().begin(1).record(4, new byte[0]).begin(2).build(),
                "ERR MALFORMED_PAYLOAD@13"));

        // =====================================================================================
        // Group E: field checks (R14-R16)
        // =====================================================================================

        // H32 field_txn_zero_is_bad_txn_id
        // Rules: R14
        // Derivation:
        //   1. Header ok. BEGIN 0 @4 (01 00 04 00 00 00 00), size 9, ends 13 (1B 05, table).
        //      COMMIT 0 @13, ends 22 (30 08, table). L = 22.
        //   2. Phase 1: both intact (R8 N = 4, R9 13 and 22 <= 22, R10 correct); p = 22 = L.
        //   3. Phase 2: BEGIN @4: R13 N = 4 ok; R14 txn = u32 00 00 00 00 = 0 -> throw
        //      BAD_TXN_ID at 4.
        // Expected: ERR BAD_TXN_ID@4
        c.add(TestCase.of("H32", "field_txn_zero_is_bad_txn_id", "R14",
                LogBuilder.wal().begin(0).commit(0).build(),
                "ERR BAD_TXN_ID@4"));

        // H33 field_key_length_zero_is_bad_key
        // Rules: R13, R15 (MIN_KEY_LEN)
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      PUT 1 key "" value "1" @13: N = 7+0+1 = 8, payload 00 00 00 01 00 00 01 31,
        //      size 13, ends 26 (96 3D, table). COMMIT 1 @26, ends 35 (31 09). L = 35.
        //   2. Phase 1: all intact, p = 35 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. PUT @13: R13 8 >= 5; k = 0; 8 >= 7+0; v = u16 at
        //      payload byte 5 = 00 01 = 1; 8 = 7+0+1 ok. R14 txn 1. R15 k = 0 < MIN_KEY_LEN 1
        //      -> throw BAD_KEY at 13.
        // Expected: ERR BAD_KEY@13
        c.add(TestCase.of("H33", "field_key_length_zero_is_bad_key", "R13,R15",
                LogBuilder.wal().begin(1).put(1, "", "1").commit(1).build(),
                "ERR BAD_KEY@13"));

        // H34 field_key_length_32_with_edge_bytes_is_ok
        // Rules: R15 (MAX_KEY_LEN, KEY_BYTE_MIN, KEY_BYTE_MAX), R22
        // Derivation:
        //   1. Key K = "!abcdefghijklmnopqrstuvwxyz0123~": 1 + 26 + 4 + 1 = 32 bytes; byte 0 is
        //      21 (= KEY_BYTE_MIN), byte 31 is 7E (= KEY_BYTE_MAX), the rest are 61-7A, 30-33.
        //   2. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      PUT 1 K = "1" @13: N = 7+32+1 = 40 (00 28), k byte 20, v 00 01, value 31,
        //      size 45, ends 58. Checksum 78 0E: s1 by hand: byte sum of type, N, txn, k, v,
        //      value = 2+0+40+0+0+0+1+32+0+1+49 = 125; key = 33 + (97+...+122 = 2847) +
        //      (48+49+50+51 = 198) + 126 = 3204; total 3329; 3329 - 13*255 = 14 = 0E;
        //      s2 = 78 from work/scratch/p6_fletcher.py.
        //      COMMIT 1 @58, ends 67 (31 09). L = 67.
        //   3. Phase 1: p = 4, 13 (R8 N = 40, R9 58 <= 67), 58 intact; p = 67 = L (R5).
        //   4. Phase 2: BEGIN 1 opens. PUT @13: R13 40 >= 5; k = 32; 40 >= 39; v = 1;
        //      40 = 7+32+1. R14 txn 1. R15 k = 32 is not > 32; every key byte is in 21..7E
        //      ("Every range includes both ends", PROMPT.md 6). R16 value 31 ok. R19 open ->
        //      write K=1. COMMIT 1 -> state {K="1"}, committed [1].
        // Expected: OK state={"!abcdefghijklmnopqrstuvwxyz0123~"="1"} committed=[1] aborted=[] incomplete=[] validLength=67 tornTail=false
        c.add(TestCase.of("H34", "field_key_length_32_with_edge_bytes_is_ok", "R15,R22",
                LogBuilder.wal().begin(1).put(1, "!abcdefghijklmnopqrstuvwxyz0123~", "1").commit(1)
                        .build(),
                "OK state={\"!abcdefghijklmnopqrstuvwxyz0123~\"=\"1\"} committed=[1] aborted=[] incomplete=[] validLength=67 tornTail=false"));

        // H35 field_key_length_33_is_bad_key
        // Rules: R13, R15 (MAX_KEY_LEN)
        // Derivation:
        //   1. Key K = "abcdefghijklmnopqrstuvwxyz0123456": 26 + 7 = 33 bytes, all in 21..7E.
        //   2. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      PUT 1 K = "1" @13: N = 7+33+1 = 41 (00 29), k byte 21, size 46, ends 59.
        //      Checksum 8F 10: s1 by hand: 2+0+41+0+0+0+1+33+0+1+49 = 127; key = 2847 +
        //      (48+...+54 = 357) = 3204; total 3331; 3331 - 13*255 = 16 = 10; s2 = 8F from
        //      work/scratch/p6_fletcher.py.
        //      COMMIT 1 @59, ends 68 (31 09). L = 68.
        //   3. Phase 1: all intact (R8 N = 41, R9 59 <= 68); p = 68 = L (R5).
        //   4. Phase 2: BEGIN 1 opens. PUT @13: R13 41 >= 5; k = 33; 41 >= 40; v = 1;
        //      41 = 7+33+1 ok. R14 txn 1. R15 k = 33 > MAX_KEY_LEN 32 -> throw BAD_KEY at 13.
        // Expected: ERR BAD_KEY@13
        c.add(TestCase.of("H35", "field_key_length_33_is_bad_key", "R13,R15",
                LogBuilder.wal().begin(1).put(1, "abcdefghijklmnopqrstuvwxyz0123456", "1").commit(1)
                        .build(),
                "ERR BAD_KEY@13"));

        // H36 field_key_with_space_is_bad_key
        // Rules: R15 (KEY_BYTE_MIN)
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      PUT 1 "a b" = "1" @13: N = 7+3+1 = 11, key bytes 61 20 62, size 16, ends 29
        //      (63 27, table). COMMIT 1 @29, ends 38 (31 09). L = 38.
        //   2. Phase 1: all intact; p = 38 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. PUT @13: R13 11 >= 5; k = 3; 11 >= 10; v = 1;
        //      11 = 7+3+1. R14 txn 1. R15 key byte 20 < KEY_BYTE_MIN 21 -> throw BAD_KEY at 13.
        // Expected: ERR BAD_KEY@13
        c.add(TestCase.of("H36", "field_key_with_space_is_bad_key", "R15",
                LogBuilder.wal().begin(1).put(1, "a b", "1").commit(1).build(),
                "ERR BAD_KEY@13"));

        // H37 field_delete_key_with_0x7f_is_bad_key
        // Rules: R15 (KEY_BYTE_MAX, DELETE)
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). PUT 1 "a"="1" @13, N = 9, size 14,
        //      ends 27 (36 A0). DELETE 1 key 61 7F @27: N = 5+2 = 7, size 12, ends 39
        //      (A2 ED, table). COMMIT 1 @39, ends 48 (31 09). L = 48.
        //   2. Phase 1: all intact; p = 48 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. PUT @13 passes R13-R16, R19 -> write a=1.
        //      DELETE @27: R13 7 >= 5; k = 2; 7 = 5+2. R14 txn 1. R15 key bytes 61 7F:
        //      7F > KEY_BYTE_MAX 7E -> throw BAD_KEY at 27.
        // Expected: ERR BAD_KEY@27
        c.add(TestCase.of("H37", "field_delete_key_with_0x7f_is_bad_key", "R15",
                LogBuilder.wal().begin(1).put(1, "a", "1").del(1, "a\u007F").commit(1).build(),
                "ERR BAD_KEY@27"));

        // H38 field_value_with_space_and_tilde_is_ok
        // Rules: R16 (VALUE_BYTE_MIN, VALUE_BYTE_MAX), R22
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      PUT 1 "v" = " ~" @13: N = 7+1+2 = 10, value bytes 20 7E, size 15, ends 28
        //      (AA 25, table). COMMIT 1 @28, ends 37 (31 09). L = 37.
        //   2. Phase 1: all intact; p = 37 = L (R5): validLength = 37, tornTail = false.
        //   3. Phase 2: BEGIN 1 opens. PUT @13: R13 10 = 7+1+2; R14 txn 1; R15 key 76; R16 value
        //      bytes 20 (= VALUE_BYTE_MIN) and 7E (= VALUE_BYTE_MAX), both in range (ends
        //      included); R19 open -> write v=" ~". COMMIT 1 -> state {v=" ~"}, committed [1].
        // Expected: OK state={"v"=" ~"} committed=[1] aborted=[] incomplete=[] validLength=37 tornTail=false
        c.add(TestCase.of("H38", "field_value_with_space_and_tilde_is_ok", "R16,R22",
                LogBuilder.wal().begin(1).put(1, "v", " ~").commit(1).build(),
                "OK state={\"v\"=\" ~\"} committed=[1] aborted=[] incomplete=[] validLength=37 tornTail=false"));

        // H39 field_value_with_0x1f_is_bad_value
        // Rules: R16 (VALUE_BYTE_MIN)
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06).
        //      PUT 1 "v" = bytes 61 1F @13: N = 10, size 15, ends 28 (CD 07, table).
        //      COMMIT 1 @28, ends 37 (31 09). L = 37.
        //   2. Phase 1: all intact; p = 37 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. PUT @13: R13 10 = 7+1+2; R14 txn 1; R15 key 76 ok;
        //      R16 value byte 1F < VALUE_BYTE_MIN 20 -> throw BAD_VALUE at 13.
        // Expected: ERR BAD_VALUE@13
        c.add(TestCase.of("H39", "field_value_with_0x1f_is_bad_value", "R16",
                LogBuilder.wal().begin(1).put(1, "v", "a\u001F").commit(1).build(),
                "ERR BAD_VALUE@13"));

        // H40 field_value_with_0x7f_is_bad_value
        // Rules: R16 (VALUE_BYTE_MAX)
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). PUT 1 "a"="1" @13, ends 27 (36 A0).
        //      PUT 1 "b" = bytes 78 7F @27: N = 7+1+2 = 10, size 15, ends 42 (F7 6A, table).
        //      COMMIT 1 @42, ends 51 (31 09). L = 51.
        //   2. Phase 1: all intact; p = 51 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. PUT @13 ok -> write a=1. PUT @27: R13 10 = 7+1+2;
        //      R14 txn 1; R15 key 62; R16 value bytes 78 ok, 7F > VALUE_BYTE_MAX 7E -> throw
        //      BAD_VALUE at 27.
        // Expected: ERR BAD_VALUE@27
        c.add(TestCase.of("H40", "field_value_with_0x7f_is_bad_value", "R16",
                LogBuilder.wal().begin(1).put(1, "a", "1").put(1, "b", "x\u007F").commit(1).build(),
                "ERR BAD_VALUE@27"));

        // =====================================================================================
        // Group F: transactions (R17-R26)
        // =====================================================================================

        // H41 txn_commit_order_not_log_order
        // Rules: R21, R22, R25; Not errors item 4 and 8
        // Derivation:
        //   1. Header ok. Records (size 9 for BEGIN/COMMIT, 14 for these PUTs, N = 9):
        //      BEGIN 7 @4, ends 13 (22 0C). BEGIN 3 @13, ends 22 (1E 08).
        //      PUT 3 "k"="b" @22, ends 36 (9B DD). PUT 7 "k"="a" @36, ends 50 (B2 E0).
        //      PUT 3 "m"="1" @50, ends 64 (72 AE). COMMIT 7 @64, ends 73 (37 0F).
        //      COMMIT 3 @73, ends 82 (33 0B). L = 82.
        //   2. Phase 1: all 7 intact; p = 82 = L (R5): validLength = 82, tornTail = false.
        //   3. Phase 2: BEGIN 7 (R17 new, R18 0 open) opens; BEGIN 3 (R18 1 open) opens.
        //      Write lists: 3 = [k=b, m=1], 7 = [k=a] (R21, log order inside each txn).
        //      COMMIT 7 @64: k=a -> state {k=a}; committed [7].
        //      COMMIT 3 @73: k=b replaces a; m=1 -> state {k=b, m=1}; committed [7, 3].
        //   (Applying writes in log order would end with k=a; listing committed by id would
        //   give [3, 7].)
        // Expected: OK state={"k"="b", "m"="1"} committed=[7, 3] aborted=[] incomplete=[] validLength=82 tornTail=false
        c.add(TestCase.of("H41", "txn_commit_order_not_log_order", "R21,R22,R25",
                LogBuilder.wal().begin(7).begin(3).put(3, "k", "b").put(7, "k", "a").put(3, "m", "1")
                        .commit(7).commit(3).build(),
                "OK state={\"k\"=\"b\", \"m\"=\"1\"} committed=[7, 3] aborted=[] incomplete=[] validLength=82 tornTail=false"));

        // H42 txn_abort_drops_writes_then_empty_commit
        // Rules: R22, R23, R25; Not errors item 3
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). PUT 1 "a"="1" @13, ends 27 (36 A0).
        //      COMMIT 1 @27, ends 36 (31 09). BEGIN 2 @36, ends 45 (1D 07).
        //      PUT 2 "a"="2" @45, ends 59 (3D A2). PUT 2 "b"="2" @59, ends 73 (41 A3).
        //      ABORT 2 @73, ends 82 (39 0B). BEGIN 3 @82, ends 91 (1E 08).
        //      COMMIT 3 @91, ends 100 (33 0B). L = 100.
        //   2. Phase 1: all 9 intact; p = 100 = L (R5).
        //   3. Phase 2: txn 1: write a=1, COMMIT -> state {a=1}, committed [1].
        //      txn 2 (R18 0 open): writes [a=2, b=2]; ABORT 2 -> writes discarded, state
        //      unchanged, aborted [2] (R23).
        //      txn 3 (R17 new, R18 0 open): COMMIT 3 with no writes -> committed [1, 3], state
        //      unchanged (txn 2's dropped writes are not applied by a later commit).
        // Expected: OK state={"a"="1"} committed=[1, 3] aborted=[2] incomplete=[] validLength=100 tornTail=false
        c.add(TestCase.of("H42", "txn_abort_drops_writes_then_empty_commit", "R22,R23,R25",
                LogBuilder.wal().begin(1).put(1, "a", "1").commit(1).begin(2).put(2, "a", "2")
                        .put(2, "b", "2").abort(2).begin(3).commit(3).build(),
                "OK state={\"a\"=\"1\"} committed=[1, 3] aborted=[2] incomplete=[] validLength=100 tornTail=false"));

        // H43 txn_incomplete_sorted_unsigned
        // Rules: R18, R26 (ids as unsigned u32)
        // Derivation:
        //   1. Header ok. BEGIN FF FF FF FF @4, ends 13 (1B 05). BEGIN 5 @13, ends 22 (20 0A).
        //      BEGIN 80 00 00 00 @22, ends 31 (1D 85). BEGIN 7F FF FF FF @31, ends 40 (19 84).
        //      L = 40. u32 values: FF FF FF FF = 4294967295; 80 00 00 00 = 128 * 2^24 =
        //      2147483648; 7F FF FF FF = 2147483647.
        //   2. Phase 1: all 4 intact; p = 40 = L (R5).
        //   3. Phase 2: each BEGIN: R13 N = 4; R14 txn != 0; R17 new id; R18 open before it
        //      = 0, 1, 2, 3 (never 4) -> opens. No COMMIT/ABORT.
        //   4. R26: open ids in ascending u32 order: 5 < 2147483647 < 2147483648 < 4294967295.
        //      (As signed 32-bit ints, 80000000 and FFFFFFFF would be negative and sort first.)
        // Expected: OK state={} committed=[] aborted=[] incomplete=[5, 2147483647, 2147483648, 4294967295] validLength=40 tornTail=false
        c.add(TestCase.of("H43", "txn_incomplete_sorted_unsigned", "R18,R26",
                LogBuilder.wal().begin(4294967295L).begin(5).begin(2147483648L).begin(2147483647L)
                        .build(),
                "OK state={} committed=[] aborted=[] incomplete=[5, 2147483647, 2147483648, 4294967295] validLength=40 tornTail=false"));

        // H44 txn_duplicate_open_id
        // Rules: R17
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). BEGIN 2 @13, ends 22 (1D 07).
        //      BEGIN 1 @22, ends 31 (1C 06). L = 31.
        //   2. Phase 1: all intact; p = 31 = L (R5).
        //   3. Phase 2: BEGIN 1 opens, BEGIN 2 opens. BEGIN @22: R13 N = 4; R14 txn 1; R17 the
        //      earlier BEGIN @4 has txn 1 (still open) -> throw DUPLICATE_TXN at 22.
        // Expected: ERR DUPLICATE_TXN@22
        c.add(TestCase.of("H44", "txn_duplicate_open_id", "R17",
                LogBuilder.wal().begin(1).begin(2).begin(1).build(),
                "ERR DUPLICATE_TXN@22"));

        // H45 txn_duplicate_committed_id
        // Rules: R17 (finished ids count)
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). PUT 1 "a"="1" @13, ends 27 (36 A0).
        //      COMMIT 1 @27, ends 36 (31 09). BEGIN 1 @36, ends 45 (1C 06).
        //      COMMIT 1 @45, ends 54 (31 09). L = 54.
        //   2. Phase 1: all intact; p = 54 = L (R5).
        //   3. Phase 2: txn 1 opens, writes a=1, commits (no longer open). BEGIN @36: R13, R14
        //      ok; R17 the earlier BEGIN @4 has txn 1 ("whether that transaction is still open,
        //      committed or aborted") -> throw DUPLICATE_TXN at 36.
        //   (Checking only the open ids would accept it and give committed = [1, 1].)
        // Expected: ERR DUPLICATE_TXN@36
        c.add(TestCase.of("H45", "txn_duplicate_committed_id", "R17",
                LogBuilder.wal().begin(1).put(1, "a", "1").commit(1).begin(1).commit(1).build(),
                "ERR DUPLICATE_TXN@36"));

        // H46 txn_put_after_commit_is_not_open
        // Rules: R19
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). COMMIT 1 @13, ends 22 (31 09).
        //      PUT 1 "a"="1" @22, N = 9, ends 36 (36 A0). L = 36.
        //   2. Phase 1: all intact; p = 36 = L (R5).
        //   3. Phase 2: BEGIN 1 opens; COMMIT 1 (no writes) -> committed, no longer open.
        //      PUT @22: R13 9 = 7+1+1; R14 txn 1; R15 key 61; R16 value 31; R19 txn 1 is
        //      already committed, not open -> throw TXN_NOT_OPEN at 22.
        // Expected: ERR TXN_NOT_OPEN@22
        c.add(TestCase.of("H46", "txn_put_after_commit_is_not_open", "R19",
                LogBuilder.wal().begin(1).commit(1).put(1, "a", "1").build(),
                "ERR TXN_NOT_OPEN@22"));

        // H47 txn_fifth_begin_too_many_open
        // Rules: R18 (MAX_OPEN_TXNS)
        // Derivation:
        //   1. Header ok. BEGIN 1, 2, 3, 4, 5 @4, 13, 22, 31, 40, each size 9 (1C 06, 1D 07,
        //      1E 08, 1F 09, 20 0A); the last ends 49. L = 49.
        //   2. Phase 1: all intact; p = 49 = L (R5).
        //   3. Phase 2: BEGIN 1-4 open, with 0, 1, 2, 3 open before each. BEGIN 5 @40: R13, R14
        //      ok; R17 new id; R18 4 = MAX_OPEN_TXNS are open -> throw TOO_MANY_OPEN at 40.
        // Expected: ERR TOO_MANY_OPEN@40
        c.add(TestCase.of("H47", "txn_fifth_begin_too_many_open", "R18",
                LogBuilder.wal().begin(1).begin(2).begin(3).begin(4).begin(5).build(),
                "ERR TOO_MANY_OPEN@40"));

        // H48 txn_commit_frees_slot
        // Rules: R18, R22, R26
        // Derivation:
        //   1. Header ok. BEGIN 1, 2, 3, 4 @4, 13, 22, 31 (ends 40). COMMIT 2 @40, ends 49
        //      (32 0A). BEGIN 5 @49, ends 58 (20 0A). L = 58.
        //   2. Phase 1: all intact; p = 58 = L (R5).
        //   3. Phase 2: 4 open {1, 2, 3, 4}. COMMIT 2 (no writes): committed [2], open
        //      {1, 3, 4}. BEGIN 5: R17 new; R18 3 open < 4 ("A transaction whose COMMIT ...
        //      has already been processed is no longer open and does not count") -> opens.
        //   4. incomplete ascending: [1, 3, 4, 5].
        // Expected: OK state={} committed=[2] aborted=[] incomplete=[1, 3, 4, 5] validLength=58 tornTail=false
        c.add(TestCase.of("H48", "txn_commit_frees_slot", "R18,R22,R26",
                LogBuilder.wal().begin(1).begin(2).begin(3).begin(4).commit(2).begin(5).build(),
                "OK state={} committed=[2] aborted=[] incomplete=[1, 3, 4, 5] validLength=58 tornTail=false"));

        // H49 txn_abort_frees_one_slot_only
        // Rules: R18, R23
        // Derivation:
        //   1. Header ok. BEGIN 1, 2, 3, 4 @4, 13, 22, 31 (ends 40). ABORT 3 @40, ends 49
        //      (3A 0C). BEGIN 5 @49, ends 58 (20 0A). BEGIN 6 @58, ends 67 (21 0B). L = 67.
        //   2. Phase 1: all intact; p = 67 = L (R5).
        //   3. Phase 2: 4 open. ABORT 3 -> open {1, 2, 4}. BEGIN 5: R18 3 open -> opens,
        //      open {1, 2, 4, 5}. BEGIN 6 @58: R17 new id; R18 4 open -> throw TOO_MANY_OPEN
        //      at 58.
        // Expected: ERR TOO_MANY_OPEN@58
        c.add(TestCase.of("H49", "txn_abort_frees_one_slot_only", "R18,R23",
                LogBuilder.wal().begin(1).begin(2).begin(3).begin(4).abort(3).begin(5).begin(6)
                        .build(),
                "ERR TOO_MANY_OPEN@58"));

        // H50 txn_delete_missing_key_in_commit_order
        // Rules: R22; Not errors item 1
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). PUT 1 "x"="1" @13, ends 27 (92 B7).
        //      BEGIN 2 @27, ends 36 (1D 07). DELETE 2 "x" @36: N = 5+1 = 6, size 11, ends 47
        //      (C5 84). DELETE 2 "y" @47, ends 58 (C6 85). COMMIT 2 @58, ends 67 (32 0A).
        //      COMMIT 1 @67, ends 76 (31 09). L = 76.
        //   2. Phase 1: all 7 intact; p = 76 = L (R5).
        //   3. Phase 2: write lists 1 = [x=1], 2 = [del x, del y].
        //      COMMIT 2 @58: state is {}: del x -> x not in state, nothing; del y -> nothing.
        //      committed [2].
        //      COMMIT 1 @67: x=1 -> state {x=1}; committed [2, 1].
        //   (Applying in log order would give x=1 then del x -> {}.)
        // Expected: OK state={"x"="1"} committed=[2, 1] aborted=[] incomplete=[] validLength=76 tornTail=false
        c.add(TestCase.of("H50", "txn_delete_missing_key_in_commit_order", "R22",
                LogBuilder.wal().begin(1).put(1, "x", "1").begin(2).del(2, "x").del(2, "y").commit(2)
                        .commit(1).build(),
                "OK state={\"x\"=\"1\"} committed=[2, 1] aborted=[] incomplete=[] validLength=76 tornTail=false"));

        // H51 txn_put_delete_put_empty_value_same_txn
        // Rules: R13, R21, R22; Not errors item 2 and 8
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). PUT 1 "a"="1" @13, ends 27 (36 A0).
        //      PUT 1 "b"="2" @27, ends 41 (3B A2). DELETE 1 "a" @41, N = 6, size 11, ends 52
        //      (AB 6C). PUT 1 "b"="" @52: N = 7+1+0 = 8, v bytes 00 00, size 13, ends 65
        //      (8E 6E). COMMIT 1 @65, ends 74 (31 09). L = 74.
        //   2. Phase 1: all 6 intact; p = 74 = L (R5).
        //   3. Phase 2: PUT @52: R13 8 >= 5, k = 1, 8 >= 8, v = 0, 8 = 7+1+0; R16 no value
        //      bytes. Write list [a=1, b=2, del a, b=""].
        //      COMMIT: a=1 -> {a=1}; b=2 -> {a=1, b=2}; del a -> {b=2}; b="" -> {b=""}.
        //      committed [1].
        // Expected: OK state={"b"=""} committed=[1] aborted=[] incomplete=[] validLength=74 tornTail=false
        c.add(TestCase.of("H51", "txn_put_delete_put_empty_value_same_txn", "R13,R21,R22",
                LogBuilder.wal().begin(1).put(1, "a", "1").put(1, "b", "2").del(1, "a").put(1, "b", "")
                        .commit(1).build(),
                "OK state={\"b\"=\"\"} committed=[1] aborted=[] incomplete=[] validLength=74 tornTail=false"));

        // H52 txn_max_id_end_to_end
        // Rules: R14, R22, R25, R26 (MAX_TXN_ID)
        // Derivation:
        //   1. Header ok. BEGIN FF FF FF FF @4, ends 13 (1B 05). BEGIN 1 @13, ends 22 (1C 06).
        //      PUT FFFFFFFF "max"="u32" @22: N = 7+3+3 = 13 (00 0D), size 18, ends 40 (DE 37).
        //      COMMIT FF FF FF FF @40, ends 49 (30 08). L = 49.
        //   2. Phase 1: all intact; p = 49 = L (R5).
        //   3. Phase 2: BEGIN 4294967295 (R14 != 0, R17 new, R18 0 open) opens; BEGIN 1 opens.
        //      PUT @22: R13 13 = 7+3+3; R15 key 6D 61 78; R16 value 75 33 32; R19 open -> write.
        //      COMMIT @40: R19 open -> state {max=u32}, committed [4294967295].
        //   4. Txn 1 still open: incomplete [1]. Ids as non-negative long (section 7.4).
        // Expected: OK state={"max"="u32"} committed=[4294967295] aborted=[] incomplete=[1] validLength=49 tornTail=false
        c.add(TestCase.of("H52", "txn_max_id_end_to_end", "R14,R22,R25,R26",
                LogBuilder.wal().begin(4294967295L).begin(1).put(4294967295L, "max", "u32")
                        .commit(4294967295L).build(),
                "OK state={\"max\"=\"u32\"} committed=[4294967295] aborted=[] incomplete=[1] validLength=49 tornTail=false"));

        // =====================================================================================
        // Group G: precedence and interactions (PROMPT.md section 8)
        // =====================================================================================

        // H53 prec_phase2_error_early_checksum_error_later
        // Rules: P2, R10, R14
        // Derivation:
        //   1. Header ok. BEGIN 0 @4, ends 13 (1B 05). BEGIN 1 @13, ends 22 (1C 06).
        //      PUT 1 "k"="v" @22, N = 9, ends 36: correct checksum A3 EF, stored A2 EE
        //      (corruptLastChecksum). COMMIT 1 @36, ends 45 (31 09). L = 45.
        //   2. Two problems: BEGIN @4 has txn 0 (R14, phase 2) and the PUT @22 has a wrong
        //      checksum with bytes after it (R10, phase 1).
        //   3. Phase 1: p = 4: R7 type 1; R8 N = 4; R9 13 <= 45; R10 correct -> intact (phase 1
        //      does not read txn). p = 13 intact. p = 22: R6 23; R7 type 2; R8 N = 9;
        //      R9 36 <= 45; R10 stored A2 EE != A3 EF and 36 < 45 -> throw CHECKSUM_MISMATCH
        //      at 22. Phase 2 never runs.
        //   4. Deciding rule P2: "Phase 1 finishes before phase 2 starts. A phase-1 error
        //      anywhere in the log beats a phase-2 error anywhere in the log, even when the
        //      phase-2 problem is in an earlier record."
        // Expected: ERR CHECKSUM_MISMATCH@22
        c.add(TestCase.of("H53", "prec_phase2_error_early_checksum_error_later", "P2,R10,R14",
                LogBuilder.wal().begin(0).begin(1).put(1, "k", "v").corruptLastChecksum().commit(1)
                        .build(),
                "ERR CHECKSUM_MISMATCH@22"));

        // H54 prec_malformed_early_too_large_later
        // Rules: P2, R8, R13
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). COMMIT with N = 0 @13 (04 00 00 0C 04),
        //      size 5, ends 18. PUT 1 "k" = 1017 x "a" @18: N = 7+1+1017 = 1025 (04 01:
        //      4*256+1), size 1030, ends 1048 (the same bytes as H16's record @13, correct
        //      checksum). COMMIT 1 @1048, ends 1057 (31 09). L = 1057.
        //   2. Two problems: the COMMIT @13 has N = 0 (R13, phase 2) and the PUT @18 has
        //      N = 1025 (R8, phase 1).
        //   3. Phase 1: p = 4 intact. p = 13: R6 1044; R7 type 4; R8 N = 0; R9 18 <= 1057;
        //      R10 correct -> intact (the payload layout is not a phase-1 check). p = 18:
        //      R6 1039; R7 type 2; R8 N = 1025 > 1024 -> throw RECORD_TOO_LARGE at 18.
        //   4. Deciding rule P2: "A phase-1 error anywhere in the log beats a phase-2 error
        //      anywhere in the log, even when the phase-2 problem is in an earlier record."
        //      MALFORMED_PAYLOAD is listed there as a phase-2 error.
        // Expected: ERR RECORD_TOO_LARGE@18
        c.add(TestCase.of("H54", "prec_malformed_early_too_large_later", "P2,R8,R13",
                LogBuilder.wal().begin(1).record(4, new byte[0]).put(1, "k", "a".repeat(1017))
                        .commit(1).build(),
                "ERR RECORD_TOO_LARGE@18"));

        // H55 prec_bad_txn_id_before_bad_key_same_record
        // Rules: P4, R14, R15
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). PUT txn 0 "a b"="1" @13: N = 7+3+1 =
        //      11, size 16, ends 29 (5B 26, table). COMMIT 1 @29, ends 38 (31 09). L = 38.
        //   2. Phase 1: all intact; p = 38 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. PUT @13 has three problems: txn 0 (R14), key byte 20
        //      (R15), txn 0 not open (R19). R13: 11 >= 5, k = 3, 11 >= 10, v = 1, 11 = 7+3+1
        //      ok. R14 txn 0 -> throw BAD_TXN_ID at 13.
        //   4. Deciding rule P4: "Within one record, the first failing check in the order R13,
        //      R14, R15, R16, R17, R18, R19 decides the code".
        // Expected: ERR BAD_TXN_ID@13
        c.add(TestCase.of("H55", "prec_bad_txn_id_before_bad_key_same_record", "P4,R14,R15",
                LogBuilder.wal().begin(1).put(0, "a b", "1").commit(1).build(),
                "ERR BAD_TXN_ID@13"));

        // H56 prec_bad_key_before_txn_not_open
        // Rules: P4, R15, R19
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). PUT txn 2 key E9 value "1" @13:
        //      N = 9, size 14, ends 27 (5E 2A, table). COMMIT 1 @27, ends 36 (31 09). L = 36.
        //   2. Phase 1: all intact; p = 36 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. PUT @13 has two problems: key byte E9 (R15) and txn 2,
        //      which never had a BEGIN (R19). R13 9 = 7+1+1; R14 txn 2; R15 key byte E9 = 233
        //      > KEY_BYTE_MAX 7E = 126 -> throw BAD_KEY at 13.
        //   4. Deciding rule P4: "the field checks (R13 to R16) always come before the
        //      transaction checks (R17 to R19)".
        // Expected: ERR BAD_KEY@13
        c.add(TestCase.of("H56", "prec_bad_key_before_txn_not_open", "P4,R15,R19",
                LogBuilder.wal().begin(1).put(2, "\u00E9", "1").commit(1).build(),
                "ERR BAD_KEY@13"));

        // H57 prec_duplicate_before_too_many_open
        // Rules: P4, R17, R18
        // Derivation:
        //   1. Header ok. BEGIN 5 @4, ends 13 (20 0A). ABORT 5 @13, ends 22 (3C 0E).
        //      BEGIN 1, 2, 3, 4 @22, 31, 40, 49 (ends 58). BEGIN 5 @58, ends 67 (20 0A). L = 67.
        //   2. Phase 1: all 7 intact; p = 67 = L (R5).
        //   3. Phase 2: BEGIN 5 opens; ABORT 5 -> aborted, no longer open. BEGIN 1-4 open (0,
        //      1, 2, 3 open before each). BEGIN 5 @58 has two problems: txn 5 was used by the
        //      BEGIN @4 (R17, aborted id) and 4 transactions are open (R18). R13, R14 ok;
        //      R17 -> throw DUPLICATE_TXN at 58.
        //   4. Deciding rule P4: "for a BEGIN record DUPLICATE_TXN (R17) comes before
        //      TOO_MANY_OPEN (R18)".
        // Expected: ERR DUPLICATE_TXN@58
        c.add(TestCase.of("H57", "prec_duplicate_before_too_many_open", "P4,R17,R18",
                LogBuilder.wal().begin(5).abort(5).begin(1).begin(2).begin(3).begin(4).begin(5)
                        .build(),
                "ERR DUPLICATE_TXN@58"));

        // H58 prec_commit_in_torn_tail_is_not_applied
        // Rules: P5, R9, R12, R25, R26
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). BEGIN 2 @13, ends 22 (1D 07).
        //      PUT 2 "a"="2" @22, ends 36 (3D A2). COMMIT 1 @36, ends 45 (31 09).
        //      COMMIT 2 @45 would end at 54; its last byte is dropped, so 8 bytes
        //      04 00 04 00 00 00 02 32 remain. L = 53.
        //   2. Phase 1: p = 4, 13, 22, 36 intact. p = 45: R5 no; R6 L-p = 8; R7 type 4;
        //      R8 N = 4; R9 45+5+4 = 54 > 53 -> torn tail at 45: validLength = 45,
        //      tornTail = true.
        //   3. Phase 2: opens 1 and 2; PUT 2 a=2 goes to txn 2's list; COMMIT 1 (no writes)
        //      -> committed [1]. The COMMIT 2 is in the torn tail and is not processed.
        //   4. Deciding rules: P5 "Bytes in the torn tail are never checked by phase 2" and
        //      R25 "A COMMIT or ABORT in the torn tail is not processed, so it is not listed."
        //      So txn 2 is still open and its write is not applied: state {}, incomplete [2].
        // Expected: OK state={} committed=[1] aborted=[] incomplete=[2] validLength=45 tornTail=true
        c.add(TestCase.of("H58", "prec_commit_in_torn_tail_is_not_applied", "P5,R9,R12,R25,R26",
                LogBuilder.wal().begin(1).begin(2).put(2, "a", "2").commit(1).commit(2).truncate(1)
                        .build(),
                "OK state={} committed=[1] aborted=[] incomplete=[2] validLength=45 tornTail=true"));

        // H59 prec_phase2_error_before_torn_tail_is_thrown
        // Rules: P5, R6, R19
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). ABORT 2 @13, ends 22 (39 0B).
        //      BEGIN 3 @22, ends 31 (1E 08). Then bytes 01 00 @31. L = 33.
        //   2. Phase 1: p = 4, 13, 22 intact. p = 31: R5 no; R6 L-p = 2 -> torn tail at 31.
        //      Phase 1 ends without throwing, so phase 2 runs.
        //   3. Phase 2: BEGIN 1 opens. ABORT @13: R13 N = 4; R14 txn 2; R19 txn 2 never had a
        //      BEGIN, not open -> throw TXN_NOT_OPEN at 13.
        //   4. Deciding rule P5: "A torn tail does not prevent errors: if an intact record
        //      before the torn tail fails a phase-2 check, recover throws that error."
        // Expected: ERR TXN_NOT_OPEN@13
        c.add(TestCase.of("H59", "prec_phase2_error_before_torn_tail_is_thrown", "P5,R6,R19",
                LogBuilder.wal().begin(1).abort(2).begin(3).bytes(0x01, 0x00).build(),
                "ERR TXN_NOT_OPEN@13"));

        // H60 prec_invalid_content_in_torn_record_is_ignored
        // Rules: P5, R10, R12; Not errors item 9
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). PUT 1 "a"="1" @13, ends 27 (36 A0).
        //      COMMIT 1 @27, ends 36 (31 09). PUT txn 0 "a b" = byte 01 @36: N = 7+3+1 = 11,
        //      size 16, ends 52; correct checksum 2B F5, stored 2A F4 (corruptLastChecksum).
        //      L = 52.
        //   2. Phase 1: p = 4, 13, 27 intact. p = 36: R6 16; R7 type 2; R8 N = 11;
        //      R9 52 <= 52; R10 stored 2A F4 != 2B F5 and 36+5+11 = 52 = L -> torn tail at
        //      36: validLength = 36, tornTail = true.
        //   3. Phase 2: txn 1 writes a=1 and commits -> state {a=1}, committed [1]. The record
        //      @36 would fail R14 (txn 0), R15 (key byte 20), R16 (value byte 01) and R19, but
        //      it is in the torn tail.
        //   4. Deciding rule P5: "Bytes in the torn tail are never checked by phase 2, whatever
        //      they contain."
        // Expected: OK state={"a"="1"} committed=[1] aborted=[] incomplete=[] validLength=36 tornTail=true
        c.add(TestCase.of("H60", "prec_invalid_content_in_torn_record_is_ignored", "P5,R10,R12",
                LogBuilder.wal().begin(1).put(1, "a", "1").commit(1).put(0, "a b", "\u0001")
                        .corruptLastChecksum().build(),
                "OK state={\"a\"=\"1\"} committed=[1] aborted=[] incomplete=[] validLength=36 tornTail=true"));

        // H61 prec_first_failing_record_wins_over_check_order
        // Rules: P4, R16, R19
        // Derivation:
        //   1. Header ok. BEGIN 1 @4, ends 13 (1C 06). COMMIT 2 @13, ends 22 (32 0A).
        //      PUT 1 "v" = byte 7F @22: N = 9, size 14, ends 36 (D8 04, table).
        //      COMMIT 1 @36, ends 45 (31 09). L = 45.
        //   2. Phase 1: all intact; p = 45 = L (R5).
        //   3. Phase 2: BEGIN 1 opens. COMMIT @13: R13 N = 4; R14 txn 2; R19 txn 2 never had a
        //      BEGIN -> throw TXN_NOT_OPEN at 13. The PUT @22 would fail R16 (BAD_VALUE),
        //      a check that comes earlier in the R13-R19 order, but it is a later record.
        //   4. Deciding rule P4: "In phase 2, the first record in log order that fails a check
        //      decides the error."
        // Expected: ERR TXN_NOT_OPEN@13
        c.add(TestCase.of("H61", "prec_first_failing_record_wins_over_check_order", "P4,R16,R19",
                LogBuilder.wal().begin(1).commit(2).put(1, "v", "\u007F").commit(1).build(),
                "ERR TXN_NOT_OPEN@13"));

        // =====================================================================================
        // Group H: API contract
        // =====================================================================================

        // H62 api_input_not_modified_rich_log
        // Rules: section 2 (must not modify log), R9, R12, R21-R26
        // Derivation:
        //   1. Header ok. Records:
        //      BEGIN FF FF FF FF @4, ends 13 (1B 05).     BEGIN 2 @13, ends 22 (1D 07).
        //      PUT FFFFFFFF "k"="v x" @22: N = 7+1+3 = 11, size 16, ends 38 (54 8B).
        //      PUT 2 "a"="" @38: N = 8, size 13, ends 51 (90 6E).
        //      DELETE 2 "k" @51: N = 6, size 11, ends 62 (B8 77).
        //      COMMIT 2 @62, ends 71 (32 0A).             BEGIN 3 @71, ends 80 (1E 08).
        //      PUT 3 "z"="~" @80: N = 9, size 14, ends 94 (F3 09).
        //      ABORT 3 @94, ends 103 (3A 0C).             COMMIT FF FF FF FF @103, ends 112 (30 08).
        //      BEGIN 00 00 00 C8 (= 200) @112, ends 121 (E3 CD).
        //      PUT 200 "q"="1" @121: N = 9, would end at 135; its last 4 bytes are dropped,
        //      so 10 bytes 02 00 09 00 00 00 C8 01 71 00 remain. L = 131.
        //   2. Phase 1: p = 4 ... 112: 11 records intact. p = 121: R5 no; R6 L-p = 10; R7 type 2;
        //      R8 N = 9; R9 121+5+9 = 135 > 131 -> torn tail at 121: validLength = 121,
        //      tornTail = true.
        //   3. Phase 2: BEGIN 4294967295 (0 open) and BEGIN 2 (1 open) open.
        //      PUT @22 -> list F = [k="v x"] (R16 76 20 78 ok). PUT @38 (v = 0, 8 = 7+1+0) ->
        //      list 2 = [a=""]. DELETE @51 (6 = 5+1) -> list 2 = [a="", del k].
        //      COMMIT 2: a="" -> {a=""}; del k: k not in state, nothing. committed [2].
        //      BEGIN 3 (1 open) opens; PUT 3 z=~ (7E ok); ABORT 3 -> dropped, aborted [3].
        //      COMMIT 4294967295: k="v x" -> {a="", k="v x"}; committed [2, 4294967295].
        //      BEGIN 200 (0 open) opens. The PUT @121 is in the torn tail.
        //   4. incomplete [200]. state sorted by key: "a" < "k".
        //   5. The harness copies the input before the call and compares it after; a changed
        //      array adds " +INPUT_MODIFIED" to the line (PROMPT.md 2: "recover must not modify
        //      the input array log").
        // Expected: OK state={"a"="", "k"="v x"} committed=[2, 4294967295] aborted=[3] incomplete=[200] validLength=121 tornTail=true
        c.add(TestCase.of("H62", "api_input_not_modified_rich_log", "R9,R12,R21,R22,R23,R24,R25,R26",
                LogBuilder.wal().begin(4294967295L).begin(2).put(4294967295L, "k", "v x")
                        .put(2, "a", "").del(2, "k").commit(2).begin(3).put(3, "z", "~").abort(3)
                        .commit(4294967295L).begin(200).put(200, "q", "1").truncate(4).build(),
                "OK state={\"a\"=\"\", \"k\"=\"v x\"} committed=[2, 4294967295] aborted=[3] incomplete=[200] validLength=121 tornTail=true"));

        // H63 api_null_input
        // Rules: R1
        // Derivation:
        //   1. log is null. R1 is the first header check: throw a NullPointerException (not a
        //      WalException). The harness renders it by class name.
        // Expected: THROW java.lang.NullPointerException
        c.add(TestCase.ofNull("H63", "api_null_input", "R1",
                "THROW java.lang.NullPointerException"));

        return c;
    }
}
