# Worked examples: hand derivations (P1)

Source of truth: `wal-recovery/task/PROMPT.md` (DRAFT v1). Rule numbers below are the R/P numbers in PROMPT.md.

How the bytes were made: `work/scratch/encode_examples.py` (encoding and checksums only, no decoding, per G3).
`work/scratch/check_prompt_hex.py` confirms that the hex in PROMPT.md equals the encoder's bytes (text comparison only).
Every checksum below was then re-computed by hand (section 0). Every outcome below was derived by hand from the
rules, not from any program output (G4).

Canonical lines for the public tests (PLAN.md §3.2), derived below:

| Example | Expected line |
|---|---|
| 1 | `OK state={"a"="1", "b"="x"} committed=[2, 1] aborted=[3] incomplete=[4] validLength=134 tornTail=false` |
| 2 | `OK state={"x"="hello"} committed=[1] aborted=[] incomplete=[2] validLength=65 tornTail=true` |
| 3 | `ERR CHECKSUM_MISMATCH@13` |

---

## 0. Checksums by hand

Fletcher-16 (PROMPT.md 5.4): s1 = (s1 + b) mod 255, s2 = (s2 + s1) mod 255 over type, N (2 bytes), payload.
Stored as s2 then s1. Because each step only adds and reduces mod 255, the final s1 is (sum of the bytes) mod 255,
and the final s2 is (sum of the running s1 values) mod 255.

### 0.1 Full step table: PUT txn 1 "a" = "1" (Example 1, offset 22)

Covered bytes: `02 00 09 00 00 00 01 01 61 00 01 31` (12 = 3 + N bytes, N = 9).

| b (hex) | 02 | 00 | 09 | 00 | 00 | 00 | 01 | 01 | 61 | 00 | 01 | 31 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| b (dec) | 2 | 0 | 9 | 0 | 0 | 0 | 1 | 1 | 97 | 0 | 1 | 49 |
| s1 | 2 | 2 | 11 | 11 | 11 | 11 | 12 | 13 | 110 | 110 | 111 | 160 |
| s2 | 2 | 4 | 15 | 26 | 37 | 48 | 60 | 73 | 183 | 293−255 = 38 | 149 | 309−255 = 54 |

s2 = 54 = 0x36, s1 = 160 = 0xA0 → stored `36 A0`. Matches the encoder. ✓

### 0.2 Full step table: Example 3 PUT at 13, undamaged and damaged

Undamaged covered bytes: `02 00 09 00 00 00 01 01 6B 00 01 76` (dec 2,0,9,0,0,0,1,1,107,0,1,118).

- s1: 2, 2, 11, 11, 11, 11, 12, 13, 120, 120, 121, 239
- s2: 2, 4, 15, 26, 37, 48, 60, 73, 193, 313−255 = 58, 179, 418−255 = 163
- s2 = 163 = 0xA3, s1 = 239 = 0xEF → the stored bytes `A3 EF` (written before the damage). ✓

Damaged covered bytes: the same, but the last byte is 0x77 = 119.

- s1: same up to 121, then 121 + 119 = 240
- s2: same up to 179, then 179 + 240 = 419 − 255 = 164
- s2 = 164 = 0xA4, s1 = 240 = 0xF0. Stored is `A3 EF`. 0xA4 ≠ 0xA3, so the checksum is wrong. ✓

### 0.3 Full step table: PUT txn 1 "x" = "hello" (Example 2, offset 13)

Covered bytes: `02 00 0D 00 00 00 01 01 78 00 05 68 65 6C 6C 6F` (dec 2,0,13,0,0,0,1,1,120,0,5,104,101,108,108,111).

- s1: 2, 2, 15, 15, 15, 15, 16, 17, 137, 137, 142, 246, 347−255 = 92, 200, 308−255 = 53, 164
- s2: 2, 4, 19, 34, 49, 64, 80, 97, 234, 371−255 = 116, 258−255 = 3, 249, 341−255 = 86, 286−255 = 31, 84, 248
- s2 = 248 = 0xF8, s1 = 164 = 0xA4 → `F8 A4`. ✓

### 0.4 Full step table: PUT txn 2 "x" = "bye" (Example 2, offset 49)

Covered bytes: `02 00 0B 00 00 00 02 01 78 00 03 62 79 65` (dec 2,0,11,0,0,0,2,1,120,0,3,98,121,101).

- s1: 2, 2, 13, 13, 13, 13, 15, 16, 136, 136, 139, 237, 358−255 = 103, 204
- s2: 2, 4, 17, 30, 43, 56, 71, 87, 223, 359−255 = 104, 243, 480−255 = 225, 328−255 = 73, 277−255 = 22
- s2 = 22 = 0x16, s1 = 204 = 0xCC → `16 CC`. ✓

### 0.5 Closed forms for the short records (t = txn id, 1 ≤ t ≤ 9, so no reduction is needed)

BEGIN t, covered `01 00 04 00 00 00 t`: s1 = 1,1,5,5,5,5,5+t; s2 = 1,2,7,12,17,22,27+t.

| Record | s2 | s1 | Stored | Check |
|---|---|---|---|---|
| BEGIN 1 | 28 = 0x1C | 6 = 0x06 | `1C 06` | ✓ (also the PROMPT.md check value) |
| BEGIN 2 | 29 = 0x1D | 7 = 0x07 | `1D 07` | ✓ |
| BEGIN 3 | 30 = 0x1E | 8 = 0x08 | `1E 08` | ✓ |
| BEGIN 4 | 31 = 0x1F | 9 = 0x09 | `1F 09` | ✓ |

COMMIT t, covered `04 00 04 00 00 00 t`: s1 = 4,4,8,8,8,8,8+t; s2 = 4,8,16,24,32,40,48+t.

| Record | s2 | s1 | Stored | Check |
|---|---|---|---|---|
| COMMIT 1 | 49 = 0x31 | 9 = 0x09 | `31 09` | ✓ |
| COMMIT 2 | 50 = 0x32 | 10 = 0x0A | `32 0A` | ✓ |

ABORT t, covered `05 00 04 00 00 00 t`: s1 = 5,5,9,9,9,9,9+t; s2 = 5,10,19,28,37,46,55+t.

| Record | s2 | s1 | Stored | Check |
|---|---|---|---|---|
| ABORT 3 | 58 = 0x3A | 12 = 0x0C | `3A 0C` | ✓ |

PUT t with a 1-byte key K and a 1-byte value V, covered `02 00 09 00 00 00 t 01 K 00 01 V`:
s1 = 2,2,11,11,11,11,11+t,12+t,12+t+K,12+t+K,13+t+K,13+t+K+V and
s2 = 2,4,15,26,37,48,59+t,71+2t,83+3t+K,95+4t+2K,108+5t+3K,121+6t+4K+V (all mod 255).

| Record | t, K, V | s1 | s2 | Stored | Check |
|---|---|---|---|---|---|
| PUT 1 "a"="1" | 1, 97, 49 | 160 = 0xA0 | 564 − 510 = 54 = 0x36 | `36 A0` | ✓ (same as 0.1) |
| PUT 2 "a"="2" | 2, 97, 50 | 162 = 0xA2 | 571 − 510 = 61 = 0x3D | `3D A2` | ✓ |
| PUT 2 "b"="x" | 2, 98, 120 | 233 = 0xE9 | 645 − 510 = 135 = 0x87 | `87 E9` | ✓ |
| PUT 3 "b"="y" | 3, 98, 121 | 235 = 0xEB | 652 − 510 = 142 = 0x8E | `8E EB` | ✓ |
| PUT 1 "k"="v" | 1, 107, 118 | 239 = 0xEF | 673 − 510 = 163 = 0xA3 | `A3 EF` | ✓ (same as 0.2) |

DELETE 4 "a", covered `03 00 06 00 00 00 04 01 61` (dec 3,0,6,0,0,0,4,1,97):
s1 = 3,3,9,9,9,9,13,14,111; s2 = 3,6,15,24,33,42,55,69,180 → s2 = 180 = 0xB4, s1 = 111 = 0x6F → `B4 6F`. ✓

---

## Example 1: interleaved transactions

L = 134.

| # | Offset | Size | Record | N | Ends at |
|---|---|---|---|---|---|
| H | 0 | 4 | header `57 41 4C 31` | | 4 |
| 1 | 4 | 9 | BEGIN 1 | 4 | 13 |
| 2 | 13 | 9 | BEGIN 2 | 4 | 22 |
| 3 | 22 | 14 | PUT 1 "a"="1" | 9 | 36 |
| 4 | 36 | 14 | PUT 2 "a"="2" | 9 | 50 |
| 5 | 50 | 14 | PUT 2 "b"="x" | 9 | 64 |
| 6 | 64 | 9 | COMMIT 2 | 4 | 73 |
| 7 | 73 | 9 | BEGIN 3 | 4 | 82 |
| 8 | 82 | 14 | PUT 3 "b"="y" | 9 | 96 |
| 9 | 96 | 9 | COMMIT 1 | 4 | 105 |
| 10 | 105 | 9 | ABORT 3 | 4 | 114 |
| 11 | 114 | 9 | BEGIN 4 | 4 | 123 |
| 12 | 123 | 11 | DELETE 4 "a" | 6 | 134 |

Sizes: BEGIN/COMMIT/ABORT 5 + 4 = 9; PUT with k = 1, v = 1: N = 7 + 1 + 1 = 9, size 14; DELETE with k = 1: N = 5 + 1 = 6, size 11.
Sum: 4 + 7 × 9 + 4 × 14 + 11 = 4 + 63 + 56 + 11 = 134 = L. ✓

### Header
1. R1: log is not null. R2: L = 134 ≠ 0. R3: L is not 1–3.
2. R4: L ≥ 4 and bytes 0–3 are `57 41 4C 31` = MAGIC. Phase 1 starts, p = 4.

### Phase 1 (R5 → R11 at each p)

| p | R5 p = L? | R6 L − p | R7 type | R8 N ≤ 1024? | R9 p+5+N ≤ L? | R10 checksum | R11 next p |
|---|---|---|---|---|---|---|---|
| 4 | no | 130 | 01 ok | 4 ok | 13 ≤ 134 | `1C 06` correct | 13 |
| 13 | no | 121 | 01 ok | 4 ok | 22 ≤ 134 | `1D 07` correct | 22 |
| 22 | no | 112 | 02 ok | 9 ok | 36 ≤ 134 | `36 A0` correct | 36 |
| 36 | no | 98 | 02 ok | 9 ok | 50 ≤ 134 | `3D A2` correct | 50 |
| 50 | no | 84 | 02 ok | 9 ok | 64 ≤ 134 | `87 E9` correct | 64 |
| 64 | no | 70 | 04 ok | 4 ok | 73 ≤ 134 | `32 0A` correct | 73 |
| 73 | no | 61 | 01 ok | 4 ok | 82 ≤ 134 | `1E 08` correct | 82 |
| 82 | no | 52 | 02 ok | 9 ok | 96 ≤ 134 | `8E EB` correct | 96 |
| 96 | no | 38 | 04 ok | 4 ok | 105 ≤ 134 | `31 09` correct | 105 |
| 105 | no | 29 | 05 ok | 4 ok | 114 ≤ 134 | `3A 0C` correct | 114 |
| 114 | no | 20 | 01 ok | 4 ok | 123 ≤ 134 | `1F 09` correct | 123 |
| 123 | no | 11 | 03 ok | 6 ok | 134 ≤ 134 | `B4 6F` correct | 134 |
| 134 | **yes** | | | | | | |

(N is the u16 at p + 1: `00 04` = 4, `00 09` = 9, `00 06` = 6. Checksums verified in section 0.)
R5 at p = 134: phase 1 ends, validLength = 134, tornTail = false. 12 intact records.

### Phase 2 (R13 → R19, then the action)

Start: open = {}, BEGIN ids seen = {}, state = {}, committed = [], aborted = [].

1. p = 4, BEGIN 1. R13: N = 4 ✓. R14: txn = `00 00 00 01` = 1 ≠ 0 ✓. R15, R16 skipped. R17: 1 not in {} ✓. R18: 0 open < 4 ✓. R19 skipped. R20: open {1}, writes(1) = []. Seen {1}.
2. p = 13, BEGIN 2. R13 ✓, R14 2 ✓, R17 2 not in {1} ✓, R18 1 open < 4 ✓. R20: open {1, 2}. Seen {1, 2}.
3. p = 22, PUT 1. R13: N = 9 ≥ 5; k = payload byte 4 = `01` = 1, 7 + 1 = 8 ≤ 9; v = u16 at payload byte 6 = `00 01` = 1, 7 + 1 + 1 = 9 = N ✓. R14: 1 ✓. R15: k = 1 in 1..32, key byte `61` in 0x21..0x7E ✓. R16: value byte `31` in 0x20..0x7E ✓. R17, R18 skipped. R19: 1 is open ✓. R21: writes(1) = [PUT a="1"].
4. p = 36, PUT 2. R13 same layout ✓. R14: 2 ✓. R15: key `61` ✓. R16: value `32` ✓. R19: 2 open ✓. R21: writes(2) = [PUT a="2"].
5. p = 50, PUT 2. R13 ✓. R14 ✓. R15: key `62` ✓. R16: value `78` ✓. R19 ✓. R21: writes(2) = [PUT a="2", PUT b="x"].
6. p = 64, COMMIT 2. R13: N = 4 ✓. R14: 2 ✓. R19: 2 open ✓. R22: apply PUT a="2" → state {a=2}; apply PUT b="x" → state {a=2, b=x}. committed = [2]. open {1}.
7. p = 73, BEGIN 3. R13 ✓. R14: 3 ✓. R17: 3 not in {1, 2} ✓. R18: 1 open < 4 ✓. R20: open {1, 3}. Seen {1, 2, 3}.
8. p = 82, PUT 3. R13 ✓. R14 ✓. R15: key `62` ✓. R16: value `79` ✓. R19: 3 open ✓. R21: writes(3) = [PUT b="y"].
9. p = 96, COMMIT 1. R13 ✓. R14: 1 ✓. R19: 1 open ✓. R22: apply PUT a="1" → state {a=1, b=x} (replaces "2"). committed = [2, 1]. open {3}.
10. p = 105, ABORT 3. R13: N = 4 ✓. R14: 3 ✓. R19: 3 open ✓. R23: discard [PUT b="y"]. aborted = [3]. open {}.
11. p = 114, BEGIN 4. R13 ✓. R14: 4 ✓. R17: 4 not in {1, 2, 3} ✓. R18: 0 open < 4 ✓. R20: open {4}. Seen {1, 2, 3, 4}.
12. p = 123, DELETE 4. R13: N = 6 ≥ 5; k = payload byte 4 = `01` = 1; 5 + 1 = 6 = N ✓. R14: 4 ✓. R15: k = 1, key byte `61` ✓. R16 skipped. R19: 4 open ✓. R21: writes(4) = [DELETE a]. State unchanged.

No check failed.

### Result (R24–R27)
- state = {"a"="1", "b"="x"} (R24)
- committed = [2, 1], aborted = [3] (R25, log order of the COMMIT/ABORT records)
- incomplete = open set {4} in ascending order = [4] (R26)
- validLength = 134, tornTail = false (R27, from R5)

Canonical: `OK state={"a"="1", "b"="x"} committed=[2, 1] aborted=[3] incomplete=[4] validLength=134 tornTail=false`

---

## Example 2: torn tail

L = 71.

| # | Offset | Size | Record | N | Ends at |
|---|---|---|---|---|---|
| H | 0 | 4 | header | | 4 |
| 1 | 4 | 9 | BEGIN 1 | 4 | 13 |
| 2 | 13 | 18 | PUT 1 "x"="hello" | 13 (`00 0D`) | 31 |
| 3 | 31 | 9 | COMMIT 1 | 4 | 40 |
| 4 | 40 | 9 | BEGIN 2 | 4 | 49 |
| 5 | 49 | 16 | PUT 2 "x"="bye" | 11 (`00 0B`) | 65 |
| 6 | 65 | 6 present of 9 | COMMIT 2, cut short: `04 00 04 00 00 00` | 4 | would be 74 |

Sizes: PUT k = 1, v = 5: N = 7 + 1 + 5 = 13, size 18. PUT k = 1, v = 3: N = 11, size 16.
Sum: 4 + 9 + 18 + 9 + 9 + 16 + 6 = 71 = L. ✓

### Header
R1 not null; R2 L ≠ 0; R3 L not 1–3; R4 bytes 0–3 are MAGIC. p = 4.

### Phase 1

| p | R5 | R6 L − p | R7 type | R8 N | R9 p+5+N vs L | R10 checksum | Outcome |
|---|---|---|---|---|---|---|---|
| 4 | no | 67 | 01 ok | 4 ok | 13 ≤ 71 | `1C 06` correct | intact, p = 13 |
| 13 | no | 58 | 02 ok | 13 ok | 31 ≤ 71 | `F8 A4` correct | intact, p = 31 |
| 31 | no | 40 | 04 ok | 4 ok | 40 ≤ 71 | `31 09` correct | intact, p = 40 |
| 40 | no | 31 | 01 ok | 4 ok | 49 ≤ 71 | `1D 07` correct | intact, p = 49 |
| 49 | no | 22 | 02 ok | 11 ok | 65 ≤ 71 | `16 CC` correct | intact, p = 65 |
| 65 | no (65 ≠ 71) | 6 (not 1 or 2) | 04 ok | bytes 66–67 `00 04` = 4 ok | 65 + 5 + 4 = 74 > 71 | not reached | **R9: torn tail at 65** |

R12: validLength = 65, tornTail = true. The torn tail is bytes 65–70. Intact records: 5 (offsets 4, 13, 31, 40, 49).

### Phase 2
Phase 1 did not throw, so phase 2 runs on the 5 intact records.

1. p = 4, BEGIN 1. R13 N = 4 ✓; R14 1 ✓; R17 1 not seen ✓; R18 0 open ✓; R20 open {1}.
2. p = 13, PUT 1. R13: N = 13 ≥ 5; k = `01` = 1, 8 ≤ 13; v = u16 at payload byte 6 = `00 05` = 5; 7 + 1 + 5 = 13 = N ✓. R14 1 ✓. R15 k = 1, key `78` ✓. R16 value bytes `68 65 6C 6C 6F` all in 0x20..0x7E ✓. R19 1 open ✓. R21 writes(1) = [PUT x="hello"].
3. p = 31, COMMIT 1. R13 ✓; R14 ✓; R19 1 open ✓. R22: state {x=hello}; committed = [1]; open {}.
4. p = 40, BEGIN 2. R13 ✓; R14 2 ✓; R17 2 not in {1} ✓; R18 0 open ✓; R20 open {2}.
5. p = 49, PUT 2. R13: N = 11; k = 1, 8 ≤ 11; v = `00 03` = 3; 7 + 1 + 3 = 11 ✓. R14 ✓. R15 key `78` ✓. R16 `62 79 65` ✓. R19 2 open ✓. R21 writes(2) = [PUT x="bye"].

The cut COMMIT at 65 is in the torn tail and is never processed (R12, P5).

### Result
- state = {"x"="hello"}; committed = [1]; aborted = []; incomplete = [2]; validLength = 65; tornTail = true.

Canonical: `OK state={"x"="hello"} committed=[1] aborted=[] incomplete=[2] validLength=65 tornTail=true`

---

## Example 3: checksum error in the middle

L = 36.

| # | Offset | Size | Record | N | Ends at |
|---|---|---|---|---|---|
| H | 0 | 4 | header | | 4 |
| 1 | 4 | 9 | BEGIN 1 | 4 | 13 |
| 2 | 13 | 14 | PUT 1 "k"="w" (value byte damaged from `76` to `77`; checksum is the one for "v") | 9 | 27 |
| 3 | 27 | 9 | COMMIT 1 | 4 | 36 |

Sum: 4 + 9 + 14 + 9 = 36 = L. ✓

The damage is 0x76 → 0x77, not a 00 ↔ FF change, so Fletcher-16 does detect it (section 0.2).

### Header
R1–R3 do not apply; R4: bytes 0–3 are MAGIC. p = 4.

### Phase 1
1. p = 4: R5 4 ≠ 36; R6 32 remain; R7 type 01 ok; R8 N = 4 ok; R9 13 ≤ 36; R10 `1C 06` correct. R11: intact, p = 13.
2. p = 13: R5 13 ≠ 36; R6 23 remain; R7 type 02 ok; R8 N = `00 09` = 9 ok; R9 13 + 5 + 9 = 27 ≤ 36 ok.
   R10: computed over bytes 13–24 (`02 00 09 00 00 00 01 01 6B 00 01 77`): s2 = 0xA4, s1 = 0xF0 (section 0.2).
   Stored: byte 25 = `A3`, byte 26 = `EF`. Byte 25 ≠ s2, so the checksum is wrong.
   The record ends at 27; 27 < 36 = L, so it does not end exactly at L → **throw CHECKSUM_MISMATCH at 13**.
3. Phase 1 stops (P3). The COMMIT at 27 is not checked. Phase 2 does not run (it runs only when phase 1 did not throw).

### Outcome
WalException, code() = CHECKSUM_MISMATCH, offset() = 13.

Canonical: `ERR CHECKSUM_MISMATCH@13`
