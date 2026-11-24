# Test audit v5: blind derivations (H09, H11, H20, H124-H162)

Written BEFORE opening any test comment, name or expected string. Inputs came from
work/scratch/p8v5t/strip_v5.py (id and input code only, plus the builder-method bodies
for H157-H162). Every outcome below is derived by hand from wal-recovery/task/PROMPT.md
(draft v5) and the LogBuilder encoding. Record sizes (§5.3): BEGIN/COMMIT/ABORT 9,
PUT 12+k+v, DELETE 10+k, SAVEPOINT/ROLLBACK 10, READ 11+k (f=0) or 13+k+v (f=1),
RANGE 15+k1+k2. All record() / builder records have the correct checksum.
Offsets and L were afterwards confirmed by work/scratch/p8v5t/Frames.java (splits by N only;
output in work/scratch/p8v5t/frames.txt): all agreed with the hand arithmetic.

## Type 11 tests

- H09: BEGIN 1 @4 (to 13); bytes 0B 00 00 @13, L = 16. p = 13: L-p = 3 (R6 no); type 11 (R7)
  -> `ERR UNKNOWN_TYPE@13`
- H11: record type 11 N=4 @4, BEGIN @13, L = 22. p = 4: R7 type 11 -> `ERR UNKNOWN_TYPE@4`
- H20: BEGIN @4; frame type 11 N=4 checksum 0000 @13 (to 22 = L). p = 13: R7 comes before R10
  -> `ERR UNKNOWN_TYPE@13`

## READ tests

- H124: BEGIN1 @4, PUT1 a=1 @13 (14), READ1 a="1" @27 (15), COMMIT1 @42, L = 51.
  R19d: write list holds PUT a=1 -> present "1" = expected. COMMIT: no other committed txn.
  -> `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[] validLength=51 tornTail=false`
- H125: BEGIN1 @4, BEGIN2 @13, PUT2 a=2 @22, COMMIT2 @36, READ1 a absent @45 (12), ABORT1 @57, L = 66.
  COMMIT2 commits a=2. READ1: snapshot of 1 (at 4) = {} -> absent = f=0, passes (not the current
  state). ABORT1.
  -> `OK state={"a"="2"} committed=[2] aborted=[1] incomplete=[] validLength=66 tornTail=false`
- H126: as H125, READ1 a="2" @45 (15). Snapshot absent, READ expects present.
  -> `ERR READ_MISMATCH@45`
- H127: BEGIN1, PUT1 a=1 @13, COMMIT1 @27, BEGIN2 @36, DEL2 a @45 (11), READ2 a absent @56 (12),
  COMMIT2 @68, L = 77. Snapshot of 2 = {a=1}, but last write of a in the list is DELETE -> absent
  = f=0. COMMIT2: txn 1's COMMIT (27) is before BEGIN2 (36) -> no conflict; DELETE a.
  -> `OK state={} committed=[1, 2] aborted=[] incomplete=[] validLength=77 tornTail=false`
- H128: BEGIN1, PUT1 a=1 @13, COMMIT1 @27, BEGIN2 @36, SAVEPOINT2 s @45 (pos 0), PUT2 a=9 @55,
  ROLLBACK2 s @69 (list emptied), READ2 a="1" @79 (15), COMMIT2 @94, L = 103.
  READ: no write of a in list -> snapshot {a=1} -> present "1" matches. COMMIT2: no conflict,
  no writes.
  -> `OK state={"a"="1"} committed=[1, 2] aborted=[] incomplete=[] validLength=103 tornTail=false`
- H129: BEGIN1 @4, BEGIN2 @13, READ1 a absent @22, PUT1 b=1 @34, PUT2 a=2 @48, COMMIT2 @62,
  COMMIT1 @71, L = 80. READ passes (snapshot {}). COMMIT2 commits a=2 (no reads). COMMIT1: U=2
  committed at 62, after BEGIN1 (4), before 71, wrote a in T's read set -> conflict -> aborted,
  b=1 discarded.
  -> `OK state={"a"="2"} committed=[2] aborted=[1] incomplete=[] validLength=80 tornTail=false`
- H130: BEGIN1, BEGIN2, PUT1 a=1 @22, PUT2 a=2 @36, COMMIT2 @50, COMMIT1 @59, L = 68.
  No READ/RANGE -> no conflict. a=2 then a=1.
  -> `OK state={"a"="1"} committed=[2, 1] aborted=[] incomplete=[] validLength=68 tornTail=false`
- H131: BEGIN1 @4, BEGIN2 @13, BEGIN3 @22, READ3 b absent @31, READ1 a absent @43, PUT2 a=2 @55,
  PUT1 b=1 @69, COMMIT2 @83, COMMIT1 @92, COMMIT3 @101, L = 110.
  COMMIT2 commits a=2. COMMIT1: U=2 wrote a (read set {a}) -> aborted. COMMIT3: read set {b};
  U=2 wrote only a; U=1 wrote b but was aborted by R22, so it does not count -> committed (no writes).
  -> `OK state={"a"="2"} committed=[2, 3] aborted=[1] incomplete=[] validLength=110 tornTail=false`
- H132: BEGIN2 @4, PUT2 a=2 @13, COMMIT2 @27, BEGIN1 @36, READ1 a="2" @45, COMMIT1 @60, L = 69.
  Snapshot of 1 = {a=2} -> matches. COMMIT1: U=2's COMMIT (27) is before BEGIN1 (36) -> no conflict.
  -> `OK state={"a"="2"} committed=[2, 1] aborted=[] incomplete=[] validLength=69 tornTail=false`
- H133: BEGIN1, BEGIN2, READ1 a absent @22, SAVEPOINT2 s @34 (pos 0), PUT2 a=2 @44, ROLLBACK2 s @58,
  PUT2 b=2 @68, COMMIT2 @82, COMMIT1 @91, L = 100. Written keys of 2 at its commit = {b} (PUT a was
  rolled back) -> no conflict with read set {a}. Both commit; 1 has no writes.
  -> `OK state={"b"="2"} committed=[2, 1] aborted=[] incomplete=[] validLength=100 tornTail=false`
- H134: BEGIN1, BEGIN2, SAVEPOINT1 s @22, READ1 a absent @32, ROLLBACK1 s @44, PUT2 a=2 @54,
  COMMIT2 @68, COMMIT1 @77, L = 86. ROLLBACK does not change the read set (still {a}). COMMIT2
  commits a=2 -> COMMIT1 conflicts -> aborted.
  -> `OK state={"a"="2"} committed=[2] aborted=[1] incomplete=[] validLength=86 tornTail=false`
- H135: BEGIN1, BEGIN2, READ1 a absent @22, DEL2 a @34, COMMIT2 @45, COMMIT1 @54, L = 63.
  COMMIT2 commits (DELETE of absent key does nothing). Its written keys {a} include T1's read key
  -> 1 aborted.
  -> `OK state={} committed=[2] aborted=[1] incomplete=[] validLength=63 tornTail=false`
- H136: READ payload 00000001 01 61 02, N = 7 @13. R13: N>=6, N>=6+k=7, f = 2 -> not well-formed.
  -> `ERR MALFORMED_PAYLOAD@13`
- H137: READ payload N = 10: k=1, f=1, N>=9, v = 0002 = 2, N must be 8+1+2 = 11 != 10.
  -> `ERR MALFORMED_PAYLOAD@13`
- H138: READ txn 7 @13; R13-R16 pass; R19 txn 7 never began -> `ERR TXN_NOT_OPEN@13`
- H139: READ value byte 0x7F (R16, f=1) -> `ERR BAD_VALUE@13`
- H140: READ key "a b" contains 0x20 (R15) -> `ERR BAD_KEY@13`
- H141: BEGIN1, PUT1 a=1 @13, COMMIT1 @27, BEGIN2 @36, READ2 a="9" @45 (15, ends at 60 = L) with
  corrupted checksum -> R10 torn tail at 45. Phase 2 never sees the READ (P5) -> no READ_MISMATCH.
  -> `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[2] validLength=45 tornTail=true`

## RANGE tests

- H142: BEGIN1, PUT1 a @13, b @27, c @41, COMMIT1 @55, BEGIN2 @64, DEL2 b @73, PUT2 d=1 @84,
  RANGE2 a..c 2 @98 (17), RANGE2 a..d 3 @115, COMMIT2 @132, L = 141. View of 2: a present (snapshot),
  b absent (own DELETE), c present, d present (own PUT). a..c -> {a, c} = 2; a..d -> {a, c, d} = 3.
  COMMIT2: no conflict; delete b, put d.
  -> `OK state={"a"="1", "c"="1", "d"="1"} committed=[1, 2] aborted=[] incomplete=[] validLength=141 tornTail=false`
- H143: BEGIN1, BEGIN2, PUT2 a=1 @22, COMMIT2 @36, RANGE1 "!".."~" 0 @45, ABORT1 @62, L = 71.
  Snapshot of 1 = {} -> n = 0 = count.
  -> `OK state={"a"="1"} committed=[2] aborted=[1] incomplete=[] validLength=71 tornTail=false`
- H144: as H143 with count 1 (current state would give 1, snapshot gives 0) -> `ERR RANGE_MISMATCH@45`
- H145: BEGIN1, PUT1 b @13, COMMIT1 @27, BEGIN2 @36, RANGE2 c..a 0 @45, COMMIT2 @62, L = 71.
  lo > hi -> n = 0 = count.
  -> `OK state={"b"="1"} committed=[1, 2] aborted=[] incomplete=[] validLength=71 tornTail=false`
- H146: as H145 with count 1 -> `ERR RANGE_MISMATCH@45`
- H147: BEGIN1, PUT1 a @13, ab @27 (15), b @42, COMMIT1 @56, BEGIN2 @65, RANGE2 a..a 1 @74 (17),
  RANGE2 ab..b 2 @91 (18), RANGE2 aa..az 1 @109 (19), COMMIT2 @128, L = 137.
  a..a = {a} 1; ab..b = {ab, b} 2 (a < ab); aa..az = {ab} 1 (a < aa as prefix, b > az). All pass.
  -> `OK state={"a"="1", "ab"="1", "b"="1"} committed=[1, 2] aborted=[] incomplete=[] validLength=137 tornTail=false`
- H148: BEGIN1, BEGIN2, RANGE1 a..c 0 @22, PUT2 b=1 @39, COMMIT2 @53, COMMIT1 @62, L = 71.
  RANGE passes (snapshot {}). COMMIT2 commits b; b in [a, c] -> 1 aborted.
  -> `OK state={"b"="1"} committed=[2] aborted=[1] incomplete=[] validLength=71 tornTail=false`
- H149: as H148 with d: d > c -> no conflict.
  -> `OK state={"d"="1"} committed=[2, 1] aborted=[] incomplete=[] validLength=71 tornTail=false`
- H150: as H148 with range c..a: lo > hi holds no key -> no conflict.
  -> `OK state={"b"="1"} committed=[2, 1] aborted=[] incomplete=[] validLength=71 tornTail=false`
- H151: BEGIN1, BEGIN2, PUT2 b=1 @22, COMMIT2 @36, RANGE1 a..c 0 @45, COMMIT1 @62, L = 71.
  RANGE: snapshot {} -> 0 passes. COMMIT1: U=2's COMMIT (36) is between BEGIN1 (4) and COMMIT1 (62);
  T's read ranges at its COMMIT include (a, c) (the RANGE came after U's commit, R22 has no
  ordering condition on it) -> conflict -> aborted.
  -> `OK state={"b"="1"} committed=[2] aborted=[1] incomplete=[] validLength=71 tornTail=false`
- H152: RANGE payload N = 11: k1 = 1, k2 = 1, N must be 10+1+1 = 12 -> `ERR MALFORMED_PAYLOAD@13`
- H153: hi "c d" has 0x20 -> `ERR BAD_KEY@13`
- H154: lo "" (k1 = 0; R13: N = 11 = 10+0+1 ok) -> R15 `ERR BAD_KEY@13`
- H155: RANGE txn 9 not open (R19 before R19e) -> `ERR TXN_NOT_OPEN@13`
- H156: BEGIN1, PUT1 a=1 @13, DEL1 a @27, RANGE1 a..a 0 @38, READ1 a absent @55, COMMIT1 @67, L = 76.
  Last write of a = DELETE -> absent: range n = 0, READ f=0 matches. COMMIT applies PUT then DELETE.
  -> `OK state={} committed=[1] aborted=[] incomplete=[] validLength=76 tornTail=false`

## Scale tests (derived by structure)

- H157 scaleManyReaders: 500000 blocks t = 1..500000, each BEGIN(9)+READ t "z" absent(12)+COMMIT(9)
  = 30 bytes, each valid (fresh id, 0 open before BEGIN, snapshot {} so z absent, no writes anywhere
  so no conflict). Then BEGIN 500001 @ 4 + 15,000,000 = 15,000,004, READ 500001 z="x" @15,000,013
  (15), L = 15,000,028. Snapshot {} -> absent, READ expects present.
  -> `ERR READ_MISMATCH@15000013`
- H158 scaleLargeState: base8 100000 keys: 12500 blocks t = 2..12501, each BEGIN + 8 PUT (key7 7 bytes,
  "v": 20 each) + COMMIT = 178 -> 2,225,000, ends 2,225,004; state k000000..k099999 = "v".
  400000 reader blocks t = 12502..412501 (30 each = 12,000,000) end 14,225,004; z never in state.
  BEGIN 412502 @14,225,004, READ 412502 k000000="w" @14,225,013 (21), L = 14,225,034.
  Snapshot has k000000 = "v" != "w".
  -> `ERR READ_MISMATCH@14225013`
- H159 scaleLongReader: 175000 blocks t = 2..175001, BEGIN+PUT a=x(14)+COMMIT = 32 -> end 5,600,004;
  BEGIN 1 @5,600,004 (snapshot a="x"); 175000 blocks t = 175002..350001 (a="y", at most 2 open)
  -> 11,200,013; 300000 READ 1 a="x" (15 each, view = snapshot, pass) -> 15,700,013;
  READ 1 a="y" @15,700,013, L = 15,700,028. View "x" != "y".
  -> `ERR READ_MISMATCH@15700013`
- H160 scaleRangeLargeState: base8 100000 keys ends 2,225,004; BEGIN 1 @2,225,004 -> 2,225,013;
  300000 RANGE 1 k000000..k999999 count 100000 (29 each; all 100000 keys lie in range) -> 10,925,013;
  RANGE count 99999 @10,925,013, L = 10,925,042.
  -> `ERR RANGE_MISMATCH@10925013`
- H161 scaleRangeGrowing: base8 50000 keys: 6250 blocks t = 2..6251 (1,112,500) end 1,112,504;
  BEGIN 1 -> 1,112,513 (snapshot 50000 keys). 150000 iterations t = 6252+i: BEGIN+PUT k(50000+i)="w"
  +COMMIT+RANGE 1 ..count 50000 = 9+20+9+29 = 67 -> 10,050,000, end 11,162,513. Each RANGE sees only
  the snapshot (50000). Final RANGE count 200000 @11,162,513, L = 11,162,542.
  -> `ERR RANGE_MISMATCH@11162513`
- H162 scaleManyRanges: BEGIN 1 @4; 200000 RANGE 1 a..a 0 (17 each, snapshot {} -> 0) -> 3,400,013;
  250000 blocks t = 2..250001 BEGIN+PUT b<6 digits of t>="x"(20)+COMMIT = 38 -> 12,900,013; no key in
  [a, a] written. COMMIT 1 @12,900,013: no conflict -> committed. READ 1 a absent @12,900,022:
  txn 1 no longer open (R19).
  -> `ERR TXN_NOT_OPEN@12900022`
