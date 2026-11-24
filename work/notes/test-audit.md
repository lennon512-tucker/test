# P7 test audit

Session P7, 2026-11-24. Auditor did not write the tests.

## Method

1. `work/scratch/p7/strip_tests.py` prints, for every test in `tests/public/PublicTests.java` and
   `tests/hidden/HiddenTests.java`, only its id and its input-building expression. Comments,
   test names, rule lists and expected strings are removed (output: `work/scratch/p7/stripped.txt`).
2. Part 1 below was written from that stripped view, `task/PROMPT.md` (DRAFT v2) and
   `runner/harness/LogBuilder.java` only, before any test comment, expected line,
   `coverage.md`, `examples.md` or `spec-audit.md` was opened.
3. Arithmetic aids only (G3): `work/scratch/p7/DumpInputs.java` prints each input's length and
   hex (encoding only, no decoding, no expected values). Every L in Part 1 was computed by hand
   first and then matched against the dump (all 66 matched). Checksums of records built by
   `begin/put/del/commit/abort/record` are correct by construction (LogBuilder.fletcher16 is
   the PROMPT.md 5.4 algorithm; `frame` writes s2 then s1). Checksums that decide an outcome
   (the frame() tests and the public examples) were computed by hand; traces are given below.
4. Part 2 compares each derived outcome with the test's expected line and derivation, checks
   the cited rules, and records fixes.

LogBuilder facts used: `wal()` = `57 41 4C 31`; record sizes BEGIN/COMMIT/ABORT 9, PUT 12+k+v,
DELETE 10+k, `record(t,p)` = 5+|p|; `corruptLastChecksum()` XORs both stored checksum bytes of
the last record written by record()/frame() with 0x01, so that checksum is always wrong (the
computed s1, s2 are 0..254, and the stored byte changes); `truncate(n)` drops the last n bytes;
`bytes(...)` appends raw bytes; `frame(t, n, payload, c)` writes t, n (u16), payload as given,
then c >> 8, c & 0xFF.

Notation: `@p` = record at offset p; `->e` = the record ends at e; M = 4294967295.

## Part 1: own derivations (from the stripped view only)

| Test | Input (from stripped code) | Own derivation (PROMPT.md rules, in check order) | Own derived outcome |
|---|---|---|---|
| P01 | Hex of Example 1 | Phase 1: @4 BEGIN1 ->13, @13 BEGIN2 ->22, @22 PUT(1,a,1) N=9 ->36, @36 PUT(2,a,2) ->50, @50 PUT(2,b,x) ->64, @64 COMMIT2 ->73, @73 BEGIN3 ->82, @82 PUT(3,b,y) ->96, @96 COMMIT1 ->105, @105 ABORT3 ->114, @114 BEGIN4 ->123, @123 DELETE(4,a) N=6 ->134. Every type 1-5, N<=1024, fits, checksum recomputed by hand and correct (e.g. PUT@22: s1 2,2,11,11,11,11,12,13,110,110,111,160; s2 2,4,15,26,37,48,60,73,183,38,149,54 -> 36 A0 = stored; DELETE@123: s2=180=B4, s1=111=6F = stored; all 12 checked). p=134=L: R5, validLength 134, tornTail false. Phase 2: R13-R19 pass for all; BEGIN 1,2 open (R20); PUTs queue (R21); COMMIT2 applies a=2,b=x (R22); BEGIN3 (1 open before); COMMIT1 applies a=1; ABORT3 drops b=y (R23); BEGIN4; DELETE queued, never applied. R26 incomplete [4]. | `OK state={"a"="1", "b"="x"} committed=[2, 1] aborted=[3] incomplete=[4] validLength=134 tornTail=false` |
| P02 | Hex of Example 2 | @4 BEGIN1 ->13; @13 PUT(1,x,hello) N=13 ->31 (checksum by hand s2=248 F8, s1=164 A4 = stored); @31 COMMIT1 ->40; @40 BEGIN2 ->49; @49 PUT(2,x,bye) N=11 ->65 (s2=22 16, s1=204 CC = stored). p=65: L-p=6 (R6 no), type 4 (R7 ok), N=4 (R8 ok), 65+5+4=74>71: R9 torn at 65 (R12). Phase 2 on 5 records: txn1 commits x=hello; txn2 open, its PUT not applied. | `OK state={"x"="hello"} committed=[1] aborted=[] incomplete=[2] validLength=65 tornTail=true` |
| P03 | Hex of Example 3 | @4 BEGIN1 ->13. @13 type 2, N=9, ->27 <= 36. Checksum of `02 00 09 00 00 00 01 01 6B 00 01 77`: s1 2,2,11,11,11,11,12,13,120,120,121,240; s2 2,4,15,26,37,48,60,73,193,58,179,164 -> A4 F0; stored A3 EF: wrong. 27<36: R10 CHECKSUM_MISMATCH at 13. | `ERR CHECKSUM_MISMATCH@13` |
| H01 | raw(), L=0 | R1 not null; R2 L=0: empty result, validLength 0, tornTail false. | `OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=false` |
| H02 | wal(), L=4 | R4 magic ok; phase 1 p=4=L: R5. No records. | `OK state={} committed=[] aborted=[] incomplete=[] validLength=4 tornTail=false` |
| H03 | `57`, L=1 | R3: equals first byte of MAGIC: empty result, validLength 0, tornTail true. | `OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=true` |
| H04 | `57 41`, L=2 | R3: equals first 2 bytes of MAGIC. | `OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=true` |
| H05 | `57 41 58`, L=3 | R3: third byte 58 != 4C: BAD_MAGIC at 0. | `ERR BAD_MAGIC@0` |
| H06 | `57 41 4C 32` + BEGIN1 + COMMIT1, L=22 | R4: byte 3 is 32 != 31: BAD_MAGIC at 0 (P1: no record read). | `ERR BAD_MAGIC@0` |
| H07 | wal BEGIN1 COMMIT1 + `04`, L=23 | @4 ->13, @13 ->22 intact. p=22: L-p=1: R6 torn at 22. Phase 2: txn1 begins and commits, no writes. | `OK state={} committed=[1] aborted=[] incomplete=[] validLength=22 tornTail=true` |
| H08 | wal BEGIN1 + `09 00`, L=15 | @4 ->13. p=13: L-p=2: R6 torn at 13 (type 09 never checked). Phase 2: txn1 open. | `OK state={} committed=[] aborted=[] incomplete=[1] validLength=13 tornTail=true` |
| H09 | wal BEGIN1 + `09 00 00`, L=16 | p=13: L-p=3 (R6 no); R7 type 9: UNKNOWN_TYPE at 13. | `ERR UNKNOWN_TYPE@13` |
| H10 | wal BEGIN1, record(0,[00 00 00 01]), COMMIT1, L=31 | @4 ->13. p=13: R7 type 0: UNKNOWN_TYPE at 13. | `ERR UNKNOWN_TYPE@13` |
| H11 | wal record(6,[00 00 00 01]), BEGIN1, L=22 | p=4: R7 type 6: UNKNOWN_TYPE at 4. | `ERR UNKNOWN_TYPE@4` |
| H12 | wal BEGIN1 PUT(1,a,1) COMMIT1 BEGIN2 PUT(2,b,2), truncate(5), L=54 | @4 ->13, @13 PUT ->27, @27 ->36, @36 ->45 intact. p=45: 9 left, type 2, N=9, 45+14=59>54: R9 torn at 45. Phase 2: txn1 commits a=1; txn2 open. | `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[2] validLength=45 tornTail=true` |
| H13 | wal BEGIN1 PUT(1,k,v) COMMIT1, corrupt COMMIT checksum, L=36 | @4, @13 ->27 intact. p=27: type 4, N=4, ->36=L fits; checksum wrong and ends exactly at L: R10 torn at 27. Phase 2: txn1 open with pending PUT. | `OK state={} committed=[] aborted=[] incomplete=[1] validLength=27 tornTail=true` |
| H14 | H13 + `00`, L=37 | p=27: ->36 <= 37 fits; checksum wrong, 36<37: R10 CHECKSUM_MISMATCH at 27. | `ERR CHECKSUM_MISMATCH@27` |
| H15 | wal BEGIN1 BEGIN2 PUT(2,x,y)* COMMIT2 COMMIT1 (* corrupted), L=54 | @4, @13 intact; @22 PUT ->36, checksum wrong, 36<54: R10 CHECKSUM_MISMATCH at 22. | `ERR CHECKSUM_MISMATCH@22` |
| H16 | wal BEGIN1 PUT(1,k,a*1017) COMMIT1, L=1052 | @13 PUT: N=7+1+1017=1025 > 1024: R8 RECORD_TOO_LARGE at 13. | `ERR RECORD_TOO_LARGE@13` |
| H17 | wal BEGIN1 PUT(1,k,a*1016) PUT(1,k,ok) COMMIT1, L=1066 | @13 PUT N=1024 (R8 ok) ->1042; @1042 PUT N=10 ->1057; @1057 COMMIT ->1066=L: R5. Phase 2: first PUT N=1024 = 7+1+1016 well-formed, key/value bytes ok; COMMIT applies k=a*1016 then k=ok (R22). | `OK state={"k"="ok"} committed=[1] aborted=[] incomplete=[] validLength=1066 tornTail=false` |
| H18 | wal BEGIN1 record(2, 32769 zero bytes) COMMIT1, L=32796 | p=13: type 2, N = u16 `80 01` = 32769 > 1024: R8 RECORD_TOO_LARGE at 13. | `ERR RECORD_TOO_LARGE@13` |
| H19 | wal BEGIN1 + `02 07 D0 00 00 00 01 01 6B`, L=22 | p=13: 9 left, type 2, N=0x07D0=2000 > 1024: R8 (before R9) RECORD_TOO_LARGE at 13. | `ERR RECORD_TOO_LARGE@13` |
| H20 | wal BEGIN1 frame(7,4,[00 00 00 01],0x0000), L=22 | p=13: 9 left; R7 type 7: UNKNOWN_TYPE at 13 (R10 never reached, although the record ends at L and its checksum 00 00 is wrong: correct is s2=70 46, s1=12 0C). | `ERR UNKNOWN_TYPE@13` |
| H21 | wal BEGIN1 frame(2,9,[00 00 00 01 01 61 00 01 31],0xC395) COMMIT1, L=36 | @13: type 2, N=9, ->27. Correct checksum over `02 00 09` + payload = 36 A0 (same bytes as P01 @22). Stored C3 95 (= Fletcher of the payload alone: s1 0,0,0,1,2,99,99,100,149; s2 0,0,0,1,3,102,201,46,195). Wrong, 27<36: R10 CHECKSUM_MISMATCH at 13. | `ERR CHECKSUM_MISMATCH@13` |
| H22 | wal BEGIN(M) PUT(M,k,v) ABORT(M), L=36 | All intact (BEGIN M checksum `01 00 04 FF FF FF FF`: s1 1,1,5,5,5,5,5; s2 1,2,7,12,17,22,27 -> 1B 05, matches dump). Phase 2: txn M != 0, opens; PUT queued; ABORT drops it. | `OK state={} committed=[] aborted=[4294967295] incomplete=[] validLength=36 tornTail=false` |
| H23 | wal BEGIN1 PUT(1,v,x*192) DEL(1,v) PUT(1,n,200) COMMIT1, L=254 | @13 PUT N=200 (`00 C8`) ->218; @218 DEL N=6 ->229; @229 PUT N=11 ->245; @245 COMMIT ->254=L. Phase 2: writes [v=x*192, del v, n=200]; COMMIT applies in order -> {n=200}. | `OK state={"n"="200"} committed=[1] aborted=[] incomplete=[] validLength=254 tornTail=false` |
| H24 | wal frame(1,4,[00 00 00 01],0x061C) BEGIN2, L=22 | @4: correct checksum 1C 06 (check value); stored 06 1C: wrong; ->13 < 22: R10 CHECKSUM_MISMATCH at 4. | `ERR CHECKSUM_MISMATCH@4` |
| H25 | wal BEGIN1 record(1,[00 00 02]) COMMIT1, L=30 | Phase 1: @13 N=3 ->21 intact, @21 ->30=L. Phase 2: @4 BEGIN1 ok; @13 BEGIN N=3 != 4: R13 MALFORMED_PAYLOAD at 13. | `ERR MALFORMED_PAYLOAD@13` |
| H26 | wal record(1,[00 00 00 01 00]) COMMIT1, L=23 | Phase 1 intact (@4 N=5 ->14, @14 ->23). Phase 2: @4 BEGIN N=5 != 4: MALFORMED_PAYLOAD at 4. | `ERR MALFORMED_PAYLOAD@4` |
| H27 | wal BEGIN1 record(2,[00 00 00 01 05 61 62 63]) COMMIT1, L=35 | Phase 2 @13 PUT N=8: N>=5 ok; k=5; N>=7+5=12 fails: MALFORMED_PAYLOAD at 13. | `ERR MALFORMED_PAYLOAD@13` |
| H28 | wal BEGIN1 record(2,[00 00 00 01 01 61 00]) COMMIT1, L=34 | @13 PUT N=7: N>=5; k=1; N>=8 fails: MALFORMED_PAYLOAD at 13. | `ERR MALFORMED_PAYLOAD@13` |
| H29 | wal BEGIN1 record(2,[00 00 00 01 01 61 00 01 31 32]) COMMIT1, L=37 | @13 PUT N=10: N>=5; k=1, N>=8; v=u16 `00 01`=1; N=7+1+1=9 != 10: MALFORMED_PAYLOAD at 13. | `ERR MALFORMED_PAYLOAD@13` |
| H30 | wal BEGIN1 record(3,[00 00 00 01]) COMMIT1, L=31 | @13 DELETE N=4 < 5: MALFORMED_PAYLOAD at 13. | `ERR MALFORMED_PAYLOAD@13` |
| H31 | wal BEGIN1 record(4,[]) BEGIN2, L=27 | Phase 1: @13 N=0 ->18 intact, @18 ->27. Phase 2: @13 COMMIT N=0 != 4: R13 MALFORMED_PAYLOAD at 13. | `ERR MALFORMED_PAYLOAD@13` |
| H32 | wal BEGIN0 COMMIT0, L=22 | Phase 2 @4: R13 ok; R14 txn 0: BAD_TXN_ID at 4. | `ERR BAD_TXN_ID@4` |
| H33 | wal BEGIN1 PUT(1,"",1) COMMIT1, L=35 | @13 PUT N=8: k=0, N>=7, v=1, N=8 ok (R13); R14 ok; R15 k=0 < 1: BAD_KEY at 13. | `ERR BAD_KEY@13` |
| H34 | wal BEGIN1 PUT(1,"!abc...xyz0123~",1) COMMIT1, L=67 | Key = `!` + 26 letters + `0123` + `~` = 32 bytes, all in 0x21-0x7E (21, 61-7A, 30-33, 7E). @13 PUT N=40 ->58; @58 COMMIT ->67. R15 passes at k=32; commit sets it. | `OK state={"!abcdefghijklmnopqrstuvwxyz0123~"="1"} committed=[1] aborted=[] incomplete=[] validLength=67 tornTail=false` |
| H35 | wal BEGIN1 PUT(1,"abc...xyz0123456",1) COMMIT1, L=68 | k=33 (26+7); N=41 well-formed; R14 ok; R15 k>32: BAD_KEY at 13. | `ERR BAD_KEY@13` |
| H36 | wal BEGIN1 PUT(1,"a b",1) COMMIT1, L=38 | @13: key byte 0x20 < 0x21: R15 BAD_KEY at 13. | `ERR BAD_KEY@13` |
| H37 | wal BEGIN1 PUT(1,a,1) DEL(1,"a\x7F") COMMIT1, L=48 | @13 PUT ok. @27 DELETE N=7 = 5+2 ok; R14 ok; R15 byte 0x7F > 0x7E: BAD_KEY at 27. | `ERR BAD_KEY@27` |
| H38 | wal BEGIN1 PUT(1,v," ~") COMMIT1, L=37 | Value bytes 0x20 and 0x7E: both ends allowed (R16). Commit sets v=" ~". | `OK state={"v"=" ~"} committed=[1] aborted=[] incomplete=[] validLength=37 tornTail=false` |
| H39 | wal BEGIN1 PUT(1,v,"a\x1F") COMMIT1, L=37 | @13: value byte 0x1F < 0x20: R16 BAD_VALUE at 13. | `ERR BAD_VALUE@13` |
| H40 | wal BEGIN1 PUT(1,a,1) PUT(1,b,"x\x7F") COMMIT1, L=51 | @13 ok; @27 PUT value byte 0x7F > 0x7E: BAD_VALUE at 27. | `ERR BAD_VALUE@27` |
| H41 | wal BEGIN7 BEGIN3 PUT(3,k,b) PUT(7,k,a) PUT(3,m,1) COMMIT7 COMMIT3, L=82 | Offsets 4,13,22,36,50,64,73 ->82=L. COMMIT7 applies k=a; COMMIT3 applies k=b, m=1 (commit order, R22). | `OK state={"k"="b", "m"="1"} committed=[7, 3] aborted=[] incomplete=[] validLength=82 tornTail=false` |
| H42 | wal BEGIN1 PUT(1,a,1) COMMIT1 BEGIN2 PUT(2,a,2) PUT(2,b,2) ABORT2 BEGIN3 COMMIT3, L=100 | Offsets 4,13,27,36,45,59,73,82,91 ->100. Commit1 a=1; abort2 drops a=2,b=2; empty commit3. | `OK state={"a"="1"} committed=[1, 3] aborted=[2] incomplete=[] validLength=100 tornTail=false` |
| H43 | wal BEGIN M, BEGIN 5, BEGIN 2147483648, BEGIN 2147483647, L=40 | 4 BEGINs, open count before each 0..3 (R18 ok). R26 ascending u32: 5 < 2147483647 < 2147483648 < M. | `OK state={} committed=[] aborted=[] incomplete=[5, 2147483647, 2147483648, 4294967295] validLength=40 tornTail=false` |
| H44 | wal BEGIN1 BEGIN2 BEGIN1, L=31 | @22 BEGIN1: R17 earlier BEGIN with txn 1 (still open): DUPLICATE_TXN at 22. | `ERR DUPLICATE_TXN@22` |
| H45 | wal BEGIN1 PUT(1,a,1) COMMIT1 BEGIN1 COMMIT1, L=54 | @36 BEGIN1: R17 earlier BEGIN 1 (committed): DUPLICATE_TXN at 36. | `ERR DUPLICATE_TXN@36` |
| H46 | wal BEGIN1 COMMIT1 PUT(1,a,1), L=36 | @22 PUT: R13-R16 ok; R19 txn 1 already committed: TXN_NOT_OPEN at 22. | `ERR TXN_NOT_OPEN@22` |
| H47 | wal BEGIN1..BEGIN5, L=49 | @40 BEGIN5: R17 new id; R18 4 open: TOO_MANY_OPEN at 40. | `ERR TOO_MANY_OPEN@40` |
| H48 | wal BEGIN1..4 COMMIT2 BEGIN5, L=58 | After COMMIT2 at 40, 3 open; BEGIN5 at 49 ok. Open {1,3,4,5}. | `OK state={} committed=[2] aborted=[] incomplete=[1, 3, 4, 5] validLength=58 tornTail=false` |
| H49 | wal BEGIN1..4 ABORT3 BEGIN5 BEGIN6, L=67 | ABORT3 at 40 frees a slot; BEGIN5 at 49 ok (3 open); BEGIN6 at 58: new id, 4 open: TOO_MANY_OPEN at 58. | `ERR TOO_MANY_OPEN@58` |
| H50 | wal BEGIN1 PUT(1,x,1) BEGIN2 DEL(2,x) DEL(2,y) COMMIT2 COMMIT1, L=76 | Offsets 4,13,27,36,47,58,67 ->76. COMMIT2: both keys absent, deletes do nothing (R22); COMMIT1 sets x=1. | `OK state={"x"="1"} committed=[2, 1] aborted=[] incomplete=[] validLength=76 tornTail=false` |
| H51 | wal BEGIN1 PUT(1,a,1) PUT(1,b,2) DEL(1,a) PUT(1,b,"") COMMIT1, L=74 | Offsets 4,13,27,41,52(N=8),65 ->74. Commit applies a=1, b=2, del a, b="" -> {b=""}. | `OK state={"b"=""} committed=[1] aborted=[] incomplete=[] validLength=74 tornTail=false` |
| H52 | wal BEGIN M, BEGIN1, PUT(M,max,u32), COMMIT M, L=49 | Offsets 4,13,22(N=13 ->40),40 ->49. Commit M sets max=u32; txn1 still open. | `OK state={"max"="u32"} committed=[4294967295] aborted=[] incomplete=[1] validLength=49 tornTail=false` |
| H53 | wal BEGIN0 BEGIN1 PUT(1,k,v)* COMMIT1, L=45 | Phase 1: @4, @13 intact; @22 PUT ->36, checksum wrong, 36<45: R10 CHECKSUM_MISMATCH at 22. The BAD_TXN_ID at 4 is a phase-2 problem and phase 2 never runs (P2). | `ERR CHECKSUM_MISMATCH@22` |
| H54 | wal BEGIN1 record(4,[]) PUT(1,k,a*1017) COMMIT1, L=1057 | Phase 1: @4 ->13; @13 COMMIT N=0 ->18 intact (frame only); @18 PUT N=1025: R8 RECORD_TOO_LARGE at 18. The malformed COMMIT at 13 is phase 2 (P2). | `ERR RECORD_TOO_LARGE@18` |
| H55 | wal BEGIN1 PUT(0,"a b",1) COMMIT1, L=38 | @13 PUT: R13 ok (N=11=7+3+1); R14 txn 0: BAD_TXN_ID at 13 (before R15's space in the key, P4). | `ERR BAD_TXN_ID@13` |
| H56 | wal BEGIN1 PUT(2,"é",1) COMMIT1, L=36 | @13 PUT: R13 ok; R14 txn 2 ok; R15 key byte 0xE9 > 0x7E: BAD_KEY at 13 (before R19, txn 2 not open; P4). | `ERR BAD_KEY@13` |
| H57 | wal BEGIN5 ABORT5 BEGIN1..4 BEGIN5, L=67 | Offsets 4,13,22,31,40,49,58. At @58: 4 open {1,2,3,4} and txn 5 had an earlier BEGIN (aborted). R17 before R18: DUPLICATE_TXN at 58. | `ERR DUPLICATE_TXN@58` |
| H58 | wal BEGIN1 BEGIN2 PUT(2,a,2) COMMIT1 COMMIT2, truncate(1), L=53 | p=45: 8 left, type 4, N=4, 45+9=54>53: R9 torn at 45. Phase 2: COMMIT1 (no writes); txn2 open, its PUT not applied (P5: COMMIT2 in the torn tail not processed). | `OK state={} committed=[1] aborted=[] incomplete=[2] validLength=45 tornTail=true` |
| H59 | wal BEGIN1 ABORT2 BEGIN3 + `01 00`, L=33 | Phase 1: @4,@13,@22 intact; p=31: 2 left: R6 torn at 31. Phase 2: @13 ABORT2: R13, R14 ok; R19 txn 2 never begun: TXN_NOT_OPEN at 13 (P5 second sentence). | `ERR TXN_NOT_OPEN@13` |
| H60 | wal BEGIN1 PUT(1,a,1) COMMIT1 PUT(0,"a b","\x01")*, L=52 | Phase 1: @36 PUT N=11 ->52=L, checksum wrong, ends exactly at L: R10 torn at 36. Phase 2 does not check it (P5). txn1 commits a=1. | `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[] validLength=36 tornTail=true` |
| H61 | wal BEGIN1 COMMIT2 PUT(1,v,"\x7F") COMMIT1, L=45 | Phase 2 @13 COMMIT2: R13, R14 ok; R19 txn 2 not open: TXN_NOT_OPEN at 13. The BAD_VALUE record at 22 comes later in log order (P4). | `ERR TXN_NOT_OPEN@13` |
| H62 | wal BEGIN M, BEGIN2, PUT(M,k,"v x"), PUT(2,a,""), DEL(2,k), COMMIT2, BEGIN3, PUT(3,z,~), ABORT3, COMMIT M, BEGIN200, PUT(200,q,1), truncate(4), L=131 | Offsets 4,13,22(N=11),38(N=8),51(N=6),62,71,80,94,103,112,121. p=121: 10 left, type 2, N=9, 121+14=135>131: R9 torn at 121. Phase 2: COMMIT2 sets a="", deletes absent k (no-op); ABORT3 drops z; COMMIT M sets k="v x"; BEGIN200 open. | `OK state={"a"="", "k"="v x"} committed=[2, 4294967295] aborted=[3] incomplete=[200] validLength=121 tornTail=true` |
| H63 | null (ofNull) | R1: NullPointerException. Rendered as the class name. | `THROW java.lang.NullPointerException` |

## Part 2: comparison with the tests (written after Part 1 and Part 3 were complete)

Method: `work/scratch/p7/compare.py` compares, as text, each own outcome (Part 1) with the test's
expected literal; `work/scratch/p7/part2.py` builds this table from the two (text handling
only). Then each derivation comment in `HiddenTests.java` was read in full: every offset, size,
L, R-check sequence and quoted rule text was checked against PROMPT.md, and every checksum trace
was recomputed (`work/scratch/p7/fletcher_check.py`, Fletcher arithmetic only: 50 traces, 0
mismatches, and each traced record's bytes occur in the dumped inputs except H60's record,
which is deliberately corrupted). Each `// Expected:` comment equals its literal (69 of 69).

Result: 66 of 66 original tests: own outcome = expected line; 0 disagreements; 0 derivation
errors that change an outcome; 0 cited rules that do not exist or do not decide the outcome.

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

## Part 3: tests added by P7 (own derivations; same rules as Part 1)

Added to close the gaps found in step 6 (see Part 5). Each was derived by hand first; the dump
tool then confirmed L and the bytes (H64 L=3; H65 L=48, DELETE stored `82 CF`; H66 L=1043, stored
`63 4C` = H16's correct `62 4D` XOR `01 01`; H67 L=36, stored `38 A1`; H68 L=49; H69 L=36, stored
`3F 81`), and `work/scratch/p7/fletcher_check.py` re-ran the three new traces (0 mismatches).

| Test | Input | Own derivation | Own derived outcome |
|---|---|---|---|
| H64 | raw `57 41 4C`, L=3 | R3: L=3 and the bytes equal the first 3 bytes of MAGIC: empty result, validLength 0, tornTail true. Largest L of R3's torn branch (H05 is L=3 with a wrong byte; H02 is L=4). | `OK state={} committed=[] aborted=[] incomplete=[] validLength=0 tornTail=true` |
| H65 | wal BEGIN1 PUT(1,a,1) record(3,[00 00 00 01 01 61 62]) COMMIT1, L=48 | Phase 1: @4 ->13, @13 ->27, @27 DELETE N=7 ->39 (checksum `03 00 07 00 00 00 01 01 61 62`: s1 3,3,10,10,10,10,11,12,109,207; s2 3,6,16,26,36,46,57,69,178,130 -> 82 CF, correct), @39 ->48=L. Phase 2: PUT ok; DELETE @27: N>=5; k=1; N=5+k=6? 7 != 6: R13 MALFORMED_PAYLOAD at 27. | `ERR MALFORMED_PAYLOAD@27` |
| H66 | wal BEGIN1 PUT(1,k,a*1017)*, L=1043 | p=13: L-p=1030; type 2; R8 N=1025 > 1024: RECORD_TOO_LARGE at 13. R9 (fits, ends 1043 = L) and R10 (wrong checksum, ends at L -> would be torn) are never reached (section 7.2, T3). | `ERR RECORD_TOO_LARGE@13` |
| H67 | wal BEGIN1 record(2,[00 00 00 01 01 61 00 02 31]) COMMIT1, L=36 | Phase 1 intact (@13 N=9 ->27; trace s1 ...,110,112,161; s2 ...,38,150,56 -> 38 A1). Phase 2 @13 PUT: N>=5; k=1; N>=8; v=u16 `00 02`=2; N=7+1+2=10 != 9: MALFORMED_PAYLOAD at 13 (H29 is the other side, N one too big). | `ERR MALFORMED_PAYLOAD@13` |
| H68 | wal BEGIN1..4 BEGIN0, L=49 | Phase 2: 4 open after @31. @40 BEGIN 0: R13 N=4 ok; R14 txn 0: BAD_TXN_ID at 40, before R18 (4 open) (P4). | `ERR BAD_TXN_ID@40` |
| H69 | wal BEGIN1 PUT(9,a,"\n") COMMIT1, L=36 | Phase 2 @13 PUT: R13 9=7+1+1; R14 txn 9; R15 key 61; R16 value byte 0A < 20: BAD_VALUE at 13, before R19 (txn 9 never begun) (P4). Trace `02 00 09 00 00 00 09 01 61 00 01 0A`: s1 ...,20,21,118,118,119,129; s2 ...,68,89,207,70,189,63 -> 3F 81. | `ERR BAD_VALUE@13` |

## Part 4: cited rules (step 4) and unstated behaviour (step 5)

### Cited rules
Every rules string (P01-P03, H01-H69) names only rules that exist in PROMPT.md DRAFT v2 (R1-R27,
P1-P5), and in every test at least one cited rule is the one that decides the outcome (Part 2,
"Derivation and rule check"). Two tests depend on text outside the R/P numbering, and both cite
it in their derivation: H22 (bytes are unsigned, sections 4 and 5.4) and H62 (input not
modified, section 2). This is not a problem, because the rules field is a list of rule numbers.
No change.

### Behaviour PROMPT.md might not state
I checked each test for any dependence on something PROMPT.md does not say:
- Output form: the harness renders `state` sorted by key, and the lists in order. This matches
  "The tests compare state as a set of key-value pairs, and compare each list element by
  element, in order" (7.4) and "The tests do not check the map's iteration order" (R24). Keys
  and values are one char per byte (5.3), which H34, H38, H51 and H62 need. Ids are
  non-negative longs (7.4), which H22, H43, H52 and H62 need.
- H63: R1 names NullPointerException, and the harness prints the class name.
- H62: section 2 ("must not modify the input array"). The harness checks this on every test.
- H18 (L = 32796) and H66 (L = 1043) are within "logs of at most 65536 bytes" (section 2).
- Every precedence test (H19, H20, H53-H61, H66, H68, H69) is decided by a sentence that states
  the order in words: section 7.2's "Because of this order, R7 and R8 apply to every record
  ...", P2, P4 and P5. None of them relies only on the order of the rule list.

The three wording points that P6 left for a reviewer:
1. R17 for an aborted id (H57). R17 says "This applies whether that transaction is still open,
   committed or aborted." It names aborted ids explicitly. Clear; no change.
2. R18's "does not count" for closed transactions (H48, H49). R18 says "If MAX_OPEN_TXNS
   transactions are open at this point (before this BEGIN record is applied)" and "A
   transaction whose COMMIT or ABORT record has already been processed is no longer open and
   does not count." Section 4 defines "open" the same way. The count can never go above 4,
   because R18 throws first (spec-audit I3), so "4 are open" cannot be read in two ways. Clear;
   no change.
3. P5's second sentence (H59). "A torn tail does not prevent errors: if an intact record before
   the torn tail fails a phase-2 check, recover throws that error." Section 7.3 says the same
   ("Phase 2 runs only when phase 1 ended without throwing (at R5 or with a torn tail)").
   Clear; no change.

Result: no test depends on unstated behaviour. PROMPT.md is unchanged (still DRAFT v2), so
spec-audit.md needs no entry, and the public tests and section 11 still match (Part 2, P01-P03).

## Part 5: coverage (step 6)

Checked against the expected lines of the 69 hidden tests (text count), PROMPT.md and PLAN.md
4.6. Gaps found and closed with H64-H69 are marked "new".

Each Code is the expected outcome of at least 2 hidden tests:

| Outcome | Hidden tests | Count |
|---|---|---|
| BAD_MAGIC | H05, H06 | 2 |
| UNKNOWN_TYPE | H09, H10, H11, H20 | 4 |
| RECORD_TOO_LARGE | H16, H18, H19, H54, H66 (new) | 5 |
| CHECKSUM_MISMATCH | H14, H15, H21, H24, H53 | 5 |
| MALFORMED_PAYLOAD | H25-H31, H65 (new), H67 (new) | 9 |
| BAD_TXN_ID | H32, H55, H68 (new) | 3 |
| BAD_KEY | H33, H35, H36, H37, H56 | 5 |
| BAD_VALUE | H39, H40, H69 (new) | 3 |
| DUPLICATE_TXN | H44, H45, H57 | 3 |
| TOO_MANY_OPEN | H47, H49 | 2 |
| TXN_NOT_OPEN | H46, H59, H61 | 3 |
| THROW java.lang.NullPointerException | H63 | 1 |
| OK | 24 tests (H01-H04, H07, H08, H12, H13, H17, H22, H23, H34, H38, H41-H43, H48, H50-H52, H58, H60, H62, H64) | 24 |

Total 69. Each rule is the deciding rule of at least one test:
R1 H63; R2 H01; R3 H03, H04, H05, H64; R4 H06; R5 H02; R6 H07, H08; R7 H09-H11, H20;
R8 H16, H18, H19, H54, H66; R9 H12, H58; R10 H13-H15, H21, H24, H53; R11 H25, H26; R12 H07, H08,
H12, H13; R13 H25-H31, H65, H67; R14 H32, H55, H68; R15 H33, H35-H37, H56; R16 H39, H40, H69;
R17 H44, H45, H57; R18 H47, H49; R19 H46, H59, H61; R20-R23 H41, H42, H50, H51 and every OK test
with a BEGIN; R24-R27 every OK test (H43 for R26's unsigned order); P1 H05, H06; P2 H53, H54;
P3 H15; P4 H55-H57, H61, H68, H69; P5 H08, H13, H58-H60.

Each side of every limit:

| Limit or boundary | Allowed side | Rejected or other side |
|---|---|---|
| MAX_PAYLOAD 1024 | H17 (N = 1024) | H16 (1025), H18 (32769), H66 (1025 on the last record) |
| MIN_TXN_ID 1 / MAX_TXN_ID 4294967295 | txn 1 everywhere; H22, H43, H52, H62 (4294967295) | H32, H55, H68 (txn 0); nothing exists above the largest u32 |
| MIN_KEY_LEN 1 / MAX_KEY_LEN 32 | 1-byte keys; H34 (32) | H33 (0), H35 (33) |
| KEY_BYTE_MIN 21 / KEY_BYTE_MAX 7E | H34 (21 and 7E) | H36 (20), H37 (7F), H56 (E9) |
| VALUE_BYTE_MIN 20 / VALUE_BYTE_MAX 7E | H38 (20 and 7E), H62 | H39 (1F), H69 (0A), H40 (7F) |
| MAX_OPEN_TXNS 4 | H43, H48 (4 open) | H47, H49 (5th BEGIN) |
| R3 L = 1..3 prefix | H03 (1), H04 (2), H64 (3, new) torn | H05 (3, not a prefix); H02 (L = 4) |
| R6 bytes left | H07 (1), H08 (2) torn | H09 (3, checked) |
| R10 end vs L | H13 (= L, torn) | H14 (< L by 1, mismatch) |
| R13 PUT N >= 7 + k | all well-formed PUTs | H28 (6 + k), H27 (key past the end) |
| R13 PUT N = 7 + k + v | all well-formed PUTs | H29 (one byte too many), H67 (one byte short, new) |
| R13 DELETE N >= 5 / N = 5 + k | H37, H50, H51, H62 | H30 (N = 4), H65 (one byte too many, new) |
| R13 BEGIN/COMMIT N = 4 | every BEGIN/COMMIT | H25 (3), H26 (5), H31 (0) |

Traps of PLAN.md 4.6:

| Trap | Tests |
|---|---|
| T1 two-phase precedence | H53, H54 |
| T2 MALFORMED_PAYLOAD is phase 2 | H25-H31, H54, H65, H67 |
| T3 R7/R8 beat R9/R10, also on the last record | H09 (R7 before R9), H20 (R7 before R10), H19 (R8 before R9), H66 (R8 before R10, new) |
| T4 R6 boundary | H07, H08, H09 |
| T5 R10 boundary | H13, H14 |
| T6 checksum covers type and N; unsigned bytes | H21, H22, H23, H24 |
| T7 txn ids >= 2^31 | H22, H43, H52, H62 |
| T8 N >= 32768 unsigned | H18 |
| T9 commit order, not log order | H41, H50 |
| T10 DUPLICATE_TXN for finished ids; before TOO_MANY_OPEN | H45, H57 |
| T11 field checks before transaction checks | H55 (R14), H56 (R15), H69 (R16, new) before R19; H68 (R14 before R18, new) |
| T12 key/value ranges and lengths at the edges | H33-H40, H56, H69 |
| T13 4-open limit; a COMMIT or ABORT frees a slot | H47, H48, H49 |
| T14 partial magic | H03, H04, H05, H64 |
| T15 empty log vs header-only log | H01, H02 |
| T16 input not modified; null input | H62 (content-rich, torn tail, all record types), H63 (TestCase.ofNull, THROW java.lang.NullPointerException); the harness also checks the input on every test |
| T17 incomplete sorted as unsigned values | H43 |

Gaps found in step 6 (all closed by new tests; none was a broken requirement, since every Code
already had 2 tests and every trap had at least one):
- R3's torn branch was never tested at its largest L (3): H64.
- R13's DELETE condition N = 5 + k never failed in any test: H65.
- R13's PUT condition N = 7 + k + v was tested only on the "one byte too many" side: H67.
- T3 lacked "R8 beats R10 on the last record": H66.
- T11 lacked a BEGIN case (R14 before R18) and the R16-before-R19 case: H68, H69.

## Part 6: fixes and changes

- Expected lines changed: none. No disagreement was found (66 of 66 original tests match the
  auditor's own derivation).
- Derivations of existing hidden tests changed: none (no errors found).
- `tests/public/PublicTests.java`: added a short hand derivation comment to P01-P03 (G4 asks for
  one next to every test; before this they only named the rules). Bytes and expected lines are
  unchanged. They still equal PROMPT.md section 11 byte for byte, and they match its expected
  results.
- Tests added: H64-H69 (group I in `HiddenTests.java`), each with rules, a step-by-step
  derivation, a checksum table for the new records, and a literal expected line (Part 3).
- Tests deleted: none.
- PROMPT.md: unchanged (DRAFT v2, not frozen). No clarification was needed (Part 4).
- Suite after the audit: 3 public + 69 hidden tests. Stub run (`work/scratch/stub`): public 0/3,
  hidden 0/69, and all 72 FAIL with `THROW java.lang.UnsupportedOperationException`, so the
  suite compiles and runs. `runner/selftest.py`: OK (10 tests).
