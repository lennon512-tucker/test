| Test | Cited rules | Own derived outcome | Test expected line | Derivation and rule check | Status |
|---|---|---|---|---|---|
| P01 | R5,R22,R23,R26 | `OK state={"a"="1", "b"="x"} committed=[2, 1] aborted=[3] incomplete=[4] validLength=134 tornTail=false` | equal to own outcome | Bytes = PROMPT.md Example 1 byte for byte (134, offsets as printed); outcome = its "Expected result". R5/R22/R23/R26 decide. Derivation (examples.md, PROMPT.md "Why", now also in PublicTests.java) agrees. | verified |
| P02 | R9,R12,R22,R26 | `OK state={"x"="hello"} committed=[1] aborted=[] incomplete=[2] validLength=65 tornTail=true` | equal to own outcome | Bytes = Example 2 (71); outcome = its Expected result. R9 + R12 decide the tail, R22/R26 the rest. Derivation agrees. | verified |
| P03 | R10 | `ERR CHECKSUM_MISMATCH@13` | equal to own outcome | Bytes = Example 3 (36); outcome = its Expected outcome. R10 decides. Checksum A4 F0 vs stored A3 EF re-done by hand. | verified |
| H01 | R2 | `OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=false` | equal to own outcome | Derivation ok. R2 decides. | verified |
| H02 | R4,R5,R27 | `OK state={} committed=[] aborted=[] incomplete=[] validLength=4 tornTail=false` | equal to own outcome | Derivation ok. R4 then R5 decide; R27 reports them. | verified |
| H03 | R3 | `OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=true` | equal to own outcome | Derivation ok. R3 (torn branch). | verified |
| H04 | R3 | `OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=true` | equal to own outcome | Derivation ok. R3 (torn branch). | verified |
| H05 | R3,P1 | `ERR BAD_MAGIC@0` | equal to own outcome | Derivation ok. R3 (BAD_MAGIC branch); P1. | verified |
| H06 | R4,P1 | `ERR BAD_MAGIC@0` | equal to own outcome | Derivation ok. R4; P1 (records never read). | verified |
| H07 | R6,R12,R22,R25 | `OK state={} committed=[1] aborted=[] incomplete=[] validLength=22 tornTail=true` | equal to own outcome | Offsets 4/13/22, COMMIT trace 31 09 ok. R6 + R12 decide the tail; R22/R25 the lists. | verified |
| H08 | R6,R12,R26,P5 | `OK state={} committed=[] aborted=[] incomplete=[1] validLength=13 tornTail=true` | equal to own outcome | Derivation ok. R6 before R7 ("whatever those bytes are"); R26. | verified |
| H09 | R6,R7 | `ERR UNKNOWN_TYPE@13` | equal to own outcome | Derivation ok. R6 does not apply (3 left); R7 decides. Also R7 before R9 (13+5+0 = 18 > 16). | verified |
| H10 | R7 | `ERR UNKNOWN_TYPE@13` | equal to own outcome | Type-0 trace 15 05 ok (dump). R7 decides. | verified |
| H11 | R7 | `ERR UNKNOWN_TYPE@4` | equal to own outcome | Type-6 trace 3F 0B ok (dump). R7 decides. | verified |
| H12 | R9,R12,R22,R26 | `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[2] validLength=45 tornTail=true` | equal to own outcome | Offsets, L = 59 - 5 = 54, R9 sum 59 > 54 ok. R9 + R12 decide. | verified |
| H13 | R10,R12,R25,P5 | `OK state={} committed=[] aborted=[] incomplete=[1] validLength=27 tornTail=true` | equal to own outcome | PUT trace A3 EF and corrupted COMMIT 30 08 ok (dump). R10 torn branch (ends at L) + R12; R25, P5. | verified |
| H14 | R10 | `ERR CHECKSUM_MISMATCH@27` | equal to own outcome | Derivation ok (36 < 37). R10 mismatch branch ("even when only 1 or 2 bytes follow"). | verified |
| H15 | R10,P3 | `ERR CHECKSUM_MISMATCH@22` | equal to own outcome | PUT x=y trace E0 01 -> stored E1 00 ok (dump). R10; P3. | verified |
| H16 | R8 | `ERR RECORD_TOO_LARGE@13` | equal to own outcome | N bytes 04 01 = 1025 ok. R8 decides. | verified |
| H17 | R8,R11,R13,R21,R22 | `OK state={"k"="ok"} committed=[1] aborted=[] incomplete=[] validLength=1066 tornTail=false` | equal to own outcome | N = 1024, v bytes 03 F8 ok; PUT checksum 13 E9 recomputed (s1 by hand, s2 by arithmetic) and in the dump; second PUT trace FE 56 ok. R8 (not >), R13, R21/R22 decide. | verified |
| H18 | R8 | `ERR RECORD_TOO_LARGE@13` | equal to own outcome | N = 0x8001 = 32769, L = 32796 <= 65536 (section 2). R8 decides (unsigned). | verified |
| H19 | R8,R9 | `ERR RECORD_TOO_LARGE@13` | equal to own outcome | N = 07 D0 = 2000. R8 before R9 decides (section 7.2 says so in words). | verified |
| H20 | R7,R10 | `ERR UNKNOWN_TYPE@13` | equal to own outcome | Correct checksum 46 0C vs stored 00 00 ok. R7 before R10 decides. | verified |
| H21 | R10 | `ERR CHECKSUM_MISMATCH@13` | equal to own outcome | Payload-only trace C3 95 ok; correct 36 A0 (= Example 1 @22). R10 (checksum covers type and N, 5.4). | verified |
| H22 | R10,R11,R23,R25 | `OK state={} committed=[] aborted=[4294967295] incomplete=[] validLength=36 tornTail=false` | equal to own outcome | FF traces 1B 05, 9D EE, 37 09 ok (dump). R10/R11 (bytes unsigned, section 4, 5.4), R23, R25 and 7.4 (non-negative long). | verified |
| H23 | R8,R10,R11,R13,R22 | `OK state={"n"="200"} committed=[1] aborted=[] incomplete=[] validLength=254 tornTail=false` | equal to own outcome | N 00 C8 = 200, v 00 C0 = 192; s1 5E by hand, 64 5E in dump; DELETE C0 81, PUT 79 13 traces ok. R8/R10/R13 (unsigned), R22. | verified |
| H24 | R10 | `ERR CHECKSUM_MISMATCH@4` | equal to own outcome | Stored 06 1C vs correct 1C 06 ok. R10 (stored order s2, s1). | verified |
| H25 | R11,R13 | `ERR MALFORMED_PAYLOAD@13` | equal to own outcome | Trace 14 06 ok. R11 (phase 1 ignores layout), R13 decides. | verified |
| H26 | R11,R13 | `ERR MALFORMED_PAYLOAD@4` | equal to own outcome | Trace 28 07 ok. R13 decides (N = 5 != 4). | verified |
| H27 | R13 | `ERR MALFORMED_PAYLOAD@13` | equal to own outcome | Trace C3 37 ok. R13 second PUT condition (N >= 7 + k) decides. | verified |
| H28 | R13 | `ERR MALFORMED_PAYLOAD@13` | equal to own outcome | Trace 16 6C ok. R13 N >= 7 + k boundary (7 < 8). | verified |
| H29 | R13 | `ERR MALFORMED_PAYLOAD@13` | equal to own outcome | Trace 14 D3 ok. R13 third PUT condition (N = 7 + k + v, one byte too many). | verified |
| H30 | R13 | `ERR MALFORMED_PAYLOAD@13` | equal to own outcome | Trace 2A 08 ok. R13 DELETE N >= 5. | verified |
| H31 | R13 | `ERR MALFORMED_PAYLOAD@13` | equal to own outcome | Trace 0C 04 ok. R13 COMMIT N = 4. | verified |
| H32 | R14 | `ERR BAD_TXN_ID@4` | equal to own outcome | Traces 1B 05, 30 08 ok. R14 decides. | verified |
| H33 | R13,R15 | `ERR BAD_KEY@13` | equal to own outcome | Trace 96 3D ok. R13 passes with k = 0 (v read at payload byte 5); R15 k < 1 decides. | verified |
| H34 | R15,R22 | `OK state={"!abcdefghijklmnopqrstuvwxyz0123~"="1"} committed=[1] aborted=[] incomplete=[] validLength=67 tornTail=false` | equal to own outcome | Key 32 bytes 21..7E; s1 0E by hand, 78 0E in dump. R15 passes at both ends ("Every range includes both ends"); R22. | verified |
| H35 | R13,R15 | `ERR BAD_KEY@13` | equal to own outcome | Key 33 bytes; s1 10 by hand, 8F 10 in dump. R15 k > 32 decides. | verified |
| H36 | R15 | `ERR BAD_KEY@13` | equal to own outcome | Trace 63 27 ok. R15 byte 20 decides. | verified |
| H37 | R15 | `ERR BAD_KEY@27` | equal to own outcome | Trace A2 ED ok. R15 byte 7F (DELETE) decides. | verified |
| H38 | R16,R22 | `OK state={"v"=" ~"} committed=[1] aborted=[] incomplete=[] validLength=37 tornTail=false` | equal to own outcome | Trace AA 25 ok. R16 passes at 20 and 7E; R22. | verified |
| H39 | R16 | `ERR BAD_VALUE@13` | equal to own outcome | Trace CD 07 ok. R16 byte 1F decides. | verified |
| H40 | R16 | `ERR BAD_VALUE@27` | equal to own outcome | Trace F7 6A ok. R16 byte 7F decides. | verified |
| H41 | R21,R22,R25 | `OK state={"k"="b", "m"="1"} committed=[7, 3] aborted=[] incomplete=[] validLength=82 tornTail=false` | equal to own outcome | Traces ok. R21/R22 (commit order), R25 (committed in log order of COMMITs). | verified |
| H42 | R22,R23,R25 | `OK state={"a"="1"} committed=[1, 3] aborted=[2] incomplete=[] validLength=100 tornTail=false` | equal to own outcome | Traces ok. R22, R23, R25; Not errors 3. | verified |
| H43 | R18,R26 | `OK state={} committed=[] aborted=[] incomplete=[5, 2147483647, 2147483648, 4294967295] validLength=40 tornTail=false` | equal to own outcome | Traces 1B 05, 20 0A, 1D 85, 19 84 ok. R18 (0-3 open) and R26 (ascending u32) decide. | verified |
| H44 | R17 | `ERR DUPLICATE_TXN@22` | equal to own outcome | Derivation ok. R17 (open id). | verified |
| H45 | R17 | `ERR DUPLICATE_TXN@36` | equal to own outcome | Derivation ok. R17 ("committed"). | verified |
| H46 | R19 | `ERR TXN_NOT_OPEN@22` | equal to own outcome | Derivation ok. R19 (already committed). | verified |
| H47 | R18 | `ERR TOO_MANY_OPEN@40` | equal to own outcome | Derivation ok. R18 (4 open). | verified |
| H48 | R18,R22,R26 | `OK state={} committed=[2] aborted=[] incomplete=[1, 3, 4, 5] validLength=58 tornTail=false` | equal to own outcome | Derivation ok. R18 second sentence ("does not count"); R26. | verified |
| H49 | R18,R23 | `ERR TOO_MANY_OPEN@58` | equal to own outcome | Derivation ok. R23 frees one slot, R18 decides at @58. | verified |
| H50 | R22 | `OK state={"x"="1"} committed=[2, 1] aborted=[] incomplete=[] validLength=76 tornTail=false` | equal to own outcome | Traces 92 B7, C5 84, C6 85 ok. R22 (DELETE of a missing key does nothing; commit order). | verified |
| H51 | R13,R21,R22 | `OK state={"b"=""} committed=[1] aborted=[] incomplete=[] validLength=74 tornTail=false` | equal to own outcome | Traces 3B A2, AB 6C, 8E 6E ok. R13 (v = 0), R21/R22 write-list order. | verified |
| H52 | R14,R22,R25,R26 | `OK state={"max"="u32"} committed=[4294967295] aborted=[] incomplete=[1] validLength=49 tornTail=false` | equal to own outcome | Trace DE 37 ok. R14 (max id valid), R22, R25, R26. | verified |
| H53 | P2,R10,R14 | `ERR CHECKSUM_MISMATCH@22` | equal to own outcome | Stored A2 EE vs A3 EF ok. P2 quote exact; R10 decides, R14 never reached. | verified |
| H54 | P2,R8,R13 | `ERR RECORD_TOO_LARGE@18` | equal to own outcome | Offsets 13 (N = 0, ends 18), 18 (N = 1025) ok. P2 quote exact; R8 decides, R13 never reached. | verified |
| H55 | P4,R14,R15 | `ERR BAD_TXN_ID@13` | equal to own outcome | Trace 5B 26 ok. P4 quote exact; R14 before R15/R19. | verified |
| H56 | P4,R15,R19 | `ERR BAD_KEY@13` | equal to own outcome | Trace 5E 2A ok. P4 quote exact; R15 before R19. | verified |
| H57 | P4,R17,R18 | `ERR DUPLICATE_TXN@58` | equal to own outcome | Offsets ok. P4 quote exact; R17 (aborted id) before R18. | verified |
| H58 | P5,R9,R12,R25,R26 | `OK state={} committed=[1] aborted=[] incomplete=[2] validLength=45 tornTail=true` | equal to own outcome | L = 54 - 1 = 53 ok. R9/R12; R25 and P5 quotes exact. | verified |
| H59 | P5,R6,R19 | `ERR TXN_NOT_OPEN@13` | equal to own outcome | Trace 39 0B ok. R6 torn at 31; P5 second sentence; R19 decides. | verified |
| H60 | P5,R10,R12 | `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[] validLength=36 tornTail=true` | equal to own outcome | Correct 2B F5, stored 2A F4 ok (dump). R10 torn branch; P5 quote exact. | verified |
| H61 | P4,R16,R19 | `ERR TXN_NOT_OPEN@13` | equal to own outcome | Trace D8 04 ok. P4 first sentence; R19 at @13 decides before R16 at @22. | verified |
| H62 | R9,R12,R21,R22,R23,R24,R25,R26 | `OK state={"a"="", "k"="v x"} committed=[2, 4294967295] aborted=[3] incomplete=[200] validLength=121 tornTail=true` | equal to own outcome | All 11 traces ok; tail 10 bytes as stated. R9/R12, R21-R26 decide; section 2 (input not modified) cited in the comment; harness adds +INPUT_MODIFIED. | verified |
| H63 | R1 | `THROW java.lang.NullPointerException` | equal to own outcome | R1 decides; ofNull; rendered as the class name. | verified |
| H64 | R3 | `OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=true` | equal to own outcome | Added by P7 (Part 3). R3 decides. | verified |
| H65 | R13 | `ERR MALFORMED_PAYLOAD@27` | equal to own outcome | Added by P7 (Part 3). R13 DELETE N = 5 + k decides. | verified |
| H66 | R8,R10 | `ERR RECORD_TOO_LARGE@13` | equal to own outcome | Added by P7 (Part 3). R8 before R10 (section 7.2 sentence) decides. | verified |
| H67 | R13 | `ERR MALFORMED_PAYLOAD@13` | equal to own outcome | Added by P7 (Part 3). R13 PUT N = 7 + k + v (one byte short) decides. | verified |
| H68 | P4,R14,R18 | `ERR BAD_TXN_ID@40` | equal to own outcome | Added by P7 (Part 3). P4; R14 before R18 decides. | verified |
| H69 | P4,R16,R19 | `ERR BAD_VALUE@13` | equal to own outcome | Added by P7 (Part 3). P4; R16 before R19 decides. | verified |
