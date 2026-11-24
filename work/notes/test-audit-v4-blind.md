# v4 (L3 CHECKPOINT) blind re-derivation

Written before opening any test comment, name or expected string. Inputs seen only through
work/scratch/p8l3a/strip_v4.py (id + input code) and work/scratch/p8l3a/inputs.txt (DumpAll hex).
Checksums re-encoded with work/scratch/p8l3a/enc.py (encode only). Outcomes derived by hand from PROMPT.md v4.

| Id | Records (offset: record) | Derivation | Expected line |
|---|---|---|---|
| P05 | 4 B2, 13 B1, 22 PUT1 a=1, 36 CK(1,2), 50 C1, 59 CK(2); L=69 | all intact (checksums re-encoded = PROMPT hex), R5 at 69. R19c at 36: open {1,2}, asc 1,2 = list. C1 applies a=1. R19c at 59: open {2} = list. R26 incomplete [2]. | `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[2] validLength=69 tornTail=false` |
| H11 | 4 type 9 N=4 (valid cks), 13 B1; L=22 | p=4: 18 bytes left, R7 type 9 not 1..8. | `ERR UNKNOWN_TYPE@4` |
| H20 | 4 B1, 13 type 9 N=4 cks 00 00 (ends at L=22) | p=4 intact; p=13: 9 left, R7 before R10 (would be torn tail by R10). | `ERR UNKNOWN_TYPE@13` |
| H102 | 4 CK() N=1, 10 B1, 19 PUT1 a=1, 33 C1; L=42 | R13 N=1, c=0, 1+0=1 ok; R14 none; R19c m=0=c ok; R23c no-op. Txn 1 commits a=1. | `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[] validLength=42 tornTail=false` |
| H103 | 4 B5, 13 B3, 22 CK(3,5); L=36 | open {5,3}, asc 3,5 = list. R26 [3, 5]. | `OK state={} committed=[] aborted=[] incomplete=[3, 5] validLength=36 tornTail=false` |
| H104 | 4 B5, 13 B3, 22 CK(5,3); L=36 | asc 3,5; listed id 1 = 5 != 3. R19c. | `ERR CHECKPOINT_MISMATCH@22` |
| H105 | 4 B4294967295, 13 B2147483648, 22 B7, 31 CK(7,2147483648,4294967295) N=13, 49 C7; L=58 | unsigned asc 7 < 2147483648 < 4294967295 = list; C7 committed; R26 asc. | `OK state={} committed=[7] aborted=[] incomplete=[2147483648, 4294967295] validLength=58 tornTail=false` |
| H106 | same, CK(2147483648,4294967295,7) at 31 | listed id 1 = 2147483648 != o_1 = 7 (signed order would pass). | `ERR CHECKPOINT_MISMATCH@31` |
| H107 | 4 B1, 13 B2, 22 C1, 31 CK(1,2); L=45 | open {2}: m=1, c=2. | `ERR CHECKPOINT_MISMATCH@31` |
| H108 | 4 B1, 13 B2, 22 CK(1); L=32 | m=2, c=1. | `ERR CHECKPOINT_MISMATCH@22` |
| H109 | 4 B1, 13 CK(1,1); L=27 | m=1, c=2 (repeated id). | `ERR CHECKPOINT_MISMATCH@13` |
| H110 | 4 CK(9), 14 B9, 23 C9; L=32 | at 4 m=0, c=1 (later BEGIN does not count). | `ERR CHECKPOINT_MISMATCH@4` |
| H111 | 4 B1, 13 CK(0,1); L=27 | R13 ok (N=9, c=2); R14 listed id 0 before R19c. | `ERR BAD_TXN_ID@13` |
| H112 | 4 B1, 13 type 8 N=0 (valid cks 18 08), 18 C1; L=27 | phase 1 ok; R13 N>=1 fails. | `ERR MALFORMED_PAYLOAD@13` |
| H113 | 4 B1, 13 type 8 N=5 payload 02 00 00 00 00, 23 C1; L=32 | R13: c=2, 1+8=9 != 5 (before R14 would see a 0 id). | `ERR MALFORMED_PAYLOAD@13` |
| H114 | 4 B1, 13 PUT1 a=1, 27 C1, 36 CK(5) bad cks, ends at 46=L | R10 end = L -> torn tail at 36; CK in torn tail not checked (P5). | `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[] validLength=36 tornTail=true` |
| H115 | 4 CK(9), 14 B1, 23 PUT1 a=1 bad cks (ends 37), 37 C1; L=46 | phase 1: R10 at 23, ends 37 < 46 -> CHECKSUM_MISMATCH; beats phase-2 mismatch at 4 (P2). | `ERR CHECKSUM_MISMATCH@23` |
| H116 | 4 B1, 13 B2, 22 A2, 31 CK(1), 41 B3, 50 PUT3 c=3, 64 C3, 73 CK(1), 83 C1; L=92 | CK at 31: open {1} ok; CK at 73: open {1} ok. Commits 3 then 1, abort 2. | `OK state={"c"="3"} committed=[3, 1] aborted=[2] incomplete=[] validLength=92 tornTail=false` |
| H117 | 4 B1, 13 PUT a=1, 27 SP 0x73 (pos 1), 37 PUT b=2, 51 CK(1), 61 RB 0x73, 71 C1; L=80 | CK ok, changes nothing (R23c) so savepoint survives; RB keeps 1 write; C1 a=1. | `OK state={"a"="1"} committed=[1] aborted=[] incomplete=[] validLength=80 tornTail=false` |
| H118 | 4 B1, 13 bytes 08 04 01 00 00; L=18 | p=13: 5 left, R7 type 8 ok, R8 N=1025 > 1024 (before R9 torn tail). | `ERR RECORD_TOO_LARGE@13` |
