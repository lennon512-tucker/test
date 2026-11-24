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
 * Groups: A header (H01-H06), B framing and tail (H07-H20), C checksum details (H21-H24).
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

        return c;
    }
}
