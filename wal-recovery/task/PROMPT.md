# WAL Crash Recovery

## 1. Overview

A small key-value store writes every change to a write-ahead log (WAL) before it changes its data.
After a crash, the store is rebuilt from the log alone.
Your job is to write `WalRecovery.recover(byte[] log)`, which reads such a log, checks it, and returns the recovered state.
Recovery has two phases: phase 1 checks the header and the frame of every record to find where the valid log ends, and phase 2 replays the intact records, checks their contents, and applies the committed transactions in commit order.
Many rules interact, so follow the stated check orders and precedence rules exactly.

## 2. What to submit

- One file named `WalRecovery.java` that contains the class `WalRecovery` from section 3.
- No `package` declaration.
- Java 21, standard library only.
- Deterministic: the outcome depends only on the bytes of `log`. The same input gives the same outcome every time, also when `recover` is called many times in one program. The outcome must not depend on the clock, randomness, the environment, the locale or hash iteration order.
- No output to stdout or stderr.
- `recover` must not modify the input array `log`: its contents are the same after the call as before it.
- For a non-null `log`, `recover` either returns a non-null `Result` or throws a `WalException`. It never throws any other exception.
- The tests call `recover` with logs of at most 65536 bytes and allow 5 seconds per call.

## 3. The fixed API

```java
// File: WalRecovery.java (no package declaration, Java 21, standard library only)
import java.util.List;
import java.util.Map;

public final class WalRecovery {
    private WalRecovery() {}

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        throw new UnsupportedOperationException("implement me");
    }

    public record Result(Map<String, String> state,
                         List<Long> committed,
                         List<Long> aborted,
                         List<Long> incomplete,
                         int validLength,
                         boolean tornTail) {}

    public enum Code {
        BAD_MAGIC, UNKNOWN_TYPE, RECORD_TOO_LARGE, CHECKSUM_MISMATCH,
        MALFORMED_PAYLOAD, BAD_TXN_ID, BAD_KEY, BAD_VALUE,
        DUPLICATE_TXN, TOO_MANY_OPEN, TXN_NOT_OPEN
    }

    public static final class WalException extends Exception {
        private final Code code;
        private final int offset;
        public WalException(Code code, int offset) {
            super(code + "@" + offset);
            this.code = code;
            this.offset = offset;
        }
        public Code code() { return code; }
        public int offset() { return offset; }
    }
}
```

The names, signatures and types above must not change: the class, the method `recover`, the record `Result` and its components, the enum `Code` and its constants, and the class `WalException` with its constructor and methods. Replace the body of `recover` with your implementation. You may add standard-library imports, private fields, private methods and private nested classes.

## 4. Definitions

- `L` is `log.length`.
- An offset is a 0-based byte position in `log`. "Byte i" means `log[i]`. "Log order" means increasing offset.
- A byte is a value from 0 to 255 (hex `00` to `FF`). Every byte is read as unsigned: for example, the byte `C8` is 200.
- All multi-byte integers are big-endian and unsigned:
  - u8 at offset i: byte i (0 to 255).
  - u16 at offset i: 256 × byte i + byte i+1 (0 to 65535).
  - u32 at offset i: byte i × 2^24 + byte i+1 × 2^16 + byte i+2 × 2^8 + byte i+3 (0 to 4294967295).
- Numbers are decimal, with two exceptions that are hex: byte values with the prefix 0x (`0x4C`), and byte values written as two hex digits in code font (`4C`), which includes the bytes in the example blocks of section 11. Offsets and lengths are always decimal.
- "The record at p" is the record whose first byte (its type byte) is at offset p. Every record error reports this p as its offset.
- "Payload byte j" of the record at p is byte p + 3 + j, with j starting at 0.
- An intact record is a record that phase 1 accepts (R11).
- A transaction is open from the moment its BEGIN record is accepted (R20) until its COMMIT or ABORT record is accepted (R22 or R23).
- The empty result is a `Result` whose `state` is an empty map and whose `committed`, `aborted` and `incomplete` are empty lists. The rule that returns it gives `validLength` and `tornTail`.

## 5. Log format

### 5.1 Header

Bytes 0 to 3 are the magic `57 41 4C 31` (ASCII "WAL1"). The first record starts at offset 4. Each further record starts right after the previous one, with no gaps and no padding.

### 5.2 Record frame

| Offset | Size | Field |
|---|---|---|
| p | 1 | type (u8) |
| p + 1 | 2 | N, the payload length (u16) |
| p + 3 | N | payload |
| p + 3 + N | 2 | checksum: byte s2, then byte s1 (section 5.4) |

The record size is 5 + N bytes. The record ends at p + 5 + N: this is the offset just after its last byte, and the offset of the next record.

### 5.3 Record types and payloads

| Type | Name | Payload fields, in order | N | Record size |
|---|---|---|---|---|
| 1 | BEGIN | txn (u32) | 4 | 9 |
| 2 | PUT | txn (u32), k (u8), key (k bytes), v (u16), value (v bytes) | 7 + k + v | 12 + k + v |
| 3 | DELETE | txn (u32), k (u8), key (k bytes) | 5 + k | 10 + k |
| 4 | COMMIT | txn (u32) | 4 | 9 |
| 5 | ABORT | txn (u32) | 4 | 9 |

- `txn` is the transaction id. `k` is the key length and `v` is the value length, both in bytes.
- Positions in the payload: txn is payload bytes 0 to 3; k is payload byte 4; the key is payload bytes 5 to 4 + k; v is the u16 at payload byte 5 + k; the value is payload bytes 7 + k to 6 + k + v.
- The table shows well-formed payloads. Rule R13 says what happens when N does not match the layout.
- In the result, each key and each value is a Java `String` with one `char` per byte, whose char code equals the byte value. A value with v = 0 is the empty string `""`.

### 5.4 Checksum (Fletcher-16)

The checksum of the record at p is computed over the 3 + N bytes at offsets p to p + 2 + N: the type byte, both bytes of N, and the whole payload. Each byte b is taken as a value from 0 to 255:

```
s1 = 0
s2 = 0
for each of these bytes b, in log order:
    s1 = (s1 + b) mod 255
    s2 = (s2 + s1) mod 255
```

The stored checksum is correct when byte p + 3 + N equals s2 and byte p + 4 + N equals s1. Otherwise it is wrong.

Check value: BEGIN txn 1 is the record `01 00 04 00 00 00 01 1C 06`. The checksum covers its first 7 bytes:

| b (hex) | `01` | `00` | `04` | `00` | `00` | `00` | `01` |
|---|---|---|---|---|---|---|---|
| s1 | 1 | 1 | 5 | 5 | 5 | 5 | 6 |
| s2 | 1 | 2 | 7 | 12 | 17 | 22 | 28 |

So s2 = 28 = `1C` and s1 = 6 = `06`, and the stored checksum bytes are `1C 06`.

## 6. Limits

| Constant | Value | Meaning |
|---|---|---|
| MAGIC | `57 41 4C 31` | header bytes 0 to 3 |
| HEADER_SIZE | 4 | offset of the first record |
| MAX_PAYLOAD | 1024 | largest allowed N |
| MIN_TXN_ID | 1 | smallest valid txn id (0 is invalid) |
| MAX_TXN_ID | 4294967295 | largest txn id (the largest u32); ids are returned as `long` values |
| MIN_KEY_LEN | 1 | shortest allowed key, in bytes |
| MAX_KEY_LEN | 32 | longest allowed key, in bytes |
| KEY_BYTE_MIN, KEY_BYTE_MAX | `0x21`, `0x7E` | allowed key bytes: printable ASCII without space |
| VALUE_BYTE_MIN, VALUE_BYTE_MAX | `0x20`, `0x7E` | allowed value bytes: printable ASCII with space |
| MAX_OPEN_TXNS | 4 | most transactions that can be open at the same time |

Every range includes both ends. A value has no length limit of its own; only N ≤ MAX_PAYLOAD limits it.

## 7. Recovery rules

"Throw CODE at x" means `throw new WalException(Code.CODE, x)`. A throw ends recovery at once.

### 7.1 Header

The checks are made in this order: R1, R2, R3, R4. The first rule that applies decides the outcome.

- **R1.** If `log` is null, throw a `NullPointerException` (not a `WalException`).
- **R2.** If L = 0, return the empty result with validLength = 0 and tornTail = false.
- **R3.** If L is 1, 2 or 3: when the L bytes equal the first L bytes of MAGIC, return the empty result with validLength = 0 and tornTail = true; otherwise throw BAD_MAGIC at 0.
- **R4.** If L ≥ 4 and bytes 0 to 3 are not MAGIC, throw BAD_MAGIC at 0. Otherwise phase 1 starts.

### 7.2 Phase 1: frames

Phase 1 walks through the records, starting with p = 4. It checks only each record's frame: the type, N, whether the record fits in the log, and the checksum. It does not check the payload contents; phase 2 does that.

For each p, the checks are made in this order: R5, R6, R7, R8, R9, R10, R11. The first rule that applies decides what happens at p. Because of this order, R7 and R8 also apply to a record that does not fit in the log.

- **R5.** If p = L, phase 1 ends with no torn tail: validLength = L and tornTail = false.
- **R6.** If L − p is 1 or 2, there is a torn tail at p (R12), whatever those bytes are.
- **R7.** If the type byte at p is not 1, 2, 3, 4 or 5, throw UNKNOWN_TYPE at p.
- **R8.** If N (the u16 at p + 1) is greater than MAX_PAYLOAD, throw RECORD_TOO_LARGE at p.
- **R9.** If p + 5 + N > L (the record does not fit in the log), there is a torn tail at p (R12).
- **R10.** If the stored checksum is wrong: when the record ends exactly at L (p + 5 + N = L), there is a torn tail at p (R12); when it ends before L (p + 5 + N < L), throw CHECKSUM_MISMATCH at p.
- **R11.** Otherwise the record is intact. Add it to the list of intact records, set p to p + 5 + N, and continue with R5.
- **R12.** A torn tail at p ends phase 1: validLength = p and tornTail = true. The bytes from p to L − 1 are the torn tail. They hold no intact records, and nothing in them is checked again.

### 7.3 Phase 2: replay

Phase 2 runs only when phase 1 ended without throwing (at R5 or with a torn tail). It processes the intact records one at a time, in log order. At the start, no transaction is open, no BEGIN record has been processed, and the state is an empty map.

For each record at p, the checks are made in this order: R13, R14, R15, R16, R17, R18, R19. Each check names the record types it applies to; for other types it is skipped. The first check that fails decides the error. If no check fails, the action for the record's type (R20 to R23) is carried out, and phase 2 moves on to the next record. R13 to R16 are field checks (they look only at the record itself); R17 to R19 are transaction checks (they also look at the earlier records).

- **R13.** Payload layout (all types). The payload is well-formed when these conditions hold, tested in this order:
  - BEGIN, COMMIT, ABORT: N = 4.
  - PUT: N ≥ 5; then, with k = payload byte 4, N ≥ 7 + k; then, with v = the u16 at payload byte 5 + k, N = 7 + k + v.
  - DELETE: N ≥ 5; then, with k = payload byte 4, N = 5 + k.

  If a condition fails, throw MALFORMED_PAYLOAD at p.
- **R14.** Txn id (all types). If txn (the u32 at payload byte 0) is 0, throw BAD_TXN_ID at p.
- **R15.** Key (PUT, DELETE). If k < MIN_KEY_LEN, or k > MAX_KEY_LEN, or any of the k key bytes is outside KEY_BYTE_MIN to KEY_BYTE_MAX, throw BAD_KEY at p.
- **R16.** Value (PUT). If any of the v value bytes is outside VALUE_BYTE_MIN to VALUE_BYTE_MAX, throw BAD_VALUE at p.
- **R17.** Reused id (BEGIN). If an earlier BEGIN record in the log has the same txn, throw DUPLICATE_TXN at p. This applies whether that transaction is still open, committed or aborted.
- **R18.** Open limit (BEGIN). If MAX_OPEN_TXNS transactions are open at this point, throw TOO_MANY_OPEN at p.
- **R19.** Open check (PUT, DELETE, COMMIT, ABORT). If the transaction txn is not open, throw TXN_NOT_OPEN at p. This covers an id that never had a BEGIN record and an id whose transaction is already committed or aborted.
- **R20.** BEGIN action: the transaction txn becomes open, with an empty write list.
- **R21.** PUT and DELETE action: append the write (PUT of key and value, or DELETE of key) to the end of the transaction's write list. The state does not change yet.
- **R22.** COMMIT action: apply the transaction's writes to the state one by one, in write-list order. A PUT sets the key to the value, adding the key or replacing its old value. A DELETE removes the key; if the key is not in the state, it does nothing. Then append txn to `committed`; the transaction is no longer open.
- **R23.** ABORT action: discard the transaction's writes without applying them. Append txn to `aborted`; the transaction is no longer open.

### 7.4 Result

If phase 2 processes every intact record without throwing, `recover` returns a `Result` with the fields below. No field and no list element is null.

- **R24.** `state`: the state after phase 2. It contains only the effects of committed transactions. The tests do not check the map's iteration order.
- **R25.** `committed`: the txn of each COMMIT record, in log order. `aborted`: the txn of each ABORT record, in log order.
- **R26.** `incomplete`: the txn of every transaction that is still open after phase 2, in ascending numeric order of the u32 values.
- **R27.** `validLength` and `tornTail`: the values set by phase 1 (R5 or R12).

The tests compare `state` as a set of key-value pairs, and compare each list element by element, in order.

## 8. Error precedence

`recover` throws at most one exception: the first one reached by the rules in section 7. These consequences of the rule order are stated here so that no doubt is left:

- **P1.** The header rules R1 to R4 come first. If one of them decides the outcome, no record is read.
- **P2.** Phase 1 runs over the whole log before phase 2 starts. A phase-1 error anywhere in the log beats a phase-2 error anywhere in the log, even when the phase-2 problem is in an earlier record. The phase-1 errors are UNKNOWN_TYPE, RECORD_TOO_LARGE and CHECKSUM_MISMATCH. The phase-2 errors are MALFORMED_PAYLOAD, BAD_TXN_ID, BAD_KEY, BAD_VALUE, DUPLICATE_TXN, TOO_MANY_OPEN and TXN_NOT_OPEN.
- **P3.** Phase 1 stops at the first p, in log order, where it throws or finds a torn tail. Nothing after that point is checked by phase 1.
- **P4.** In phase 2, the first record in log order that fails a check decides the error. Within one record, the first failing check in the order R13, R14, R15, R16, R17, R18, R19 decides the code, so the field checks (R13 to R16) always come before the transaction checks (R17 to R19).
- **P5.** Bytes in the torn tail are never checked by phase 2, whatever they contain. A torn tail does not prevent errors: if an intact record before the torn tail fails a phase-2 check, `recover` throws that error.

## 9. Error table

The offset p is the offset of the type byte of the record that fails.

| Outcome | When | Rule | Offset reported |
|---|---|---|---|
| `NullPointerException` | `log` is null | R1 | none |
| BAD_MAGIC | L is 1 to 3 and the bytes are not the first L bytes of MAGIC; or L ≥ 4 and bytes 0 to 3 are not MAGIC | R3, R4 | 0 |
| UNKNOWN_TYPE | at least 3 bytes remain at p and the type byte is not 1 to 5 | R7 | p |
| RECORD_TOO_LARGE | at least 3 bytes remain at p, the type byte is 1 to 5, and N > 1024 | R8 | p |
| CHECKSUM_MISMATCH | the record fits, its stored checksum is wrong, and it ends before L | R10 | p |
| MALFORMED_PAYLOAD | the payload layout does not match the record type | R13 | p |
| BAD_TXN_ID | txn is 0 | R14 | p |
| BAD_KEY | k is 0, k is more than 32, or a key byte is outside `0x21` to `0x7E` | R15 | p |
| BAD_VALUE | a value byte is outside `0x20` to `0x7E` | R16 | p |
| DUPLICATE_TXN | BEGIN with a txn used by an earlier BEGIN record | R17 | p |
| TOO_MANY_OPEN | BEGIN while 4 transactions are open | R18 | p |
| TXN_NOT_OPEN | PUT, DELETE, COMMIT or ABORT for a transaction that is not open | R19 | p |

## 10. Not errors

These cases are valid and must not throw:

1. A DELETE of a key that is not in the state when its transaction commits. The DELETE does nothing (R22).
2. A PUT with an empty value (v = 0). It sets the key to `""` when its transaction commits.
3. A COMMIT of a transaction with no writes. Its txn is appended to `committed`, and the state does not change.
4. Txn ids in any order. Ids do not need to increase or be consecutive; any id from 1 to 4294967295 is valid.
5. A log of exactly the 4 header bytes. The result is the empty result with validLength = 4 and tornTail = false (R5).
6. 1 or 2 bytes left after the last intact record. They are a torn tail (R6).
7. A transaction that is never committed or aborted. Its txn is in `incomplete`, and its writes are never applied.
8. Several transactions that write the same key, or one transaction that writes the same key more than once. The writes are applied at commit time in the order of R22, so the write applied last decides the final value.
9. A torn tail whose bytes would break a rule if they were checked (P5).

## 11. Worked examples

Bytes are in hex. Each line is one record: its offset (decimal), its bytes, and what it is. In the expected results, `{"a"="1"}` is a map in which the key "a" has the value "1", and `[2, 1]` is a list with the elements 2 and 1, in that order.

### Example 1: interleaved transactions

Story: transactions 1 and 2 run at the same time and transaction 2 commits first; transaction 3 aborts; transaction 4 is still open at the crash.

```
offset  bytes                                       record
     0  57 41 4C 31                                 header
     4  01 00 04 00 00 00 01 1C 06                  BEGIN txn 1
    13  01 00 04 00 00 00 02 1D 07                  BEGIN txn 2
    22  02 00 09 00 00 00 01 01 61 00 01 31 36 A0   PUT txn 1, "a" = "1"
    36  02 00 09 00 00 00 02 01 61 00 01 32 3D A2   PUT txn 2, "a" = "2"
    50  02 00 09 00 00 00 02 01 62 00 01 78 87 E9   PUT txn 2, "b" = "x"
    64  04 00 04 00 00 00 02 32 0A                  COMMIT txn 2
    73  01 00 04 00 00 00 03 1E 08                  BEGIN txn 3
    82  02 00 09 00 00 00 03 01 62 00 01 79 8E EB   PUT txn 3, "b" = "y"
    96  04 00 04 00 00 00 01 31 09                  COMMIT txn 1
   105  05 00 04 00 00 00 03 3A 0C                  ABORT txn 3
   114  01 00 04 00 00 00 04 1F 09                  BEGIN txn 4
   123  03 00 06 00 00 00 04 01 61 B4 6F            DELETE txn 4, "a"
L = 134
```

The PUT at 22, split into fields: type `02`, N `00 09`, txn `00 00 00 01`, k `01`, key `61` ("a"), v `00 01`, value `31` ("1"), checksum `36 A0`.

Expected result:

```
state       = {"a"="1", "b"="x"}
committed   = [2, 1]
aborted     = [3]
incomplete  = [4]
validLength = 134
tornTail    = false
```

Why: all 12 records are intact, and phase 1 ends at p = 134 = L (R5). The COMMIT at 64 applies txn 2's writes: a = "2", b = "x". The COMMIT at 96 then applies txn 1's write a = "1", which replaces "2". The ABORT at 105 drops txn 3's write b = "y". Txn 4 is still open, so its DELETE is never applied.

### Example 2: torn tail

Story: the crash happened while the COMMIT of transaction 2 was being written, so only its first 6 bytes reached the log.

```
offset  bytes                                                   record
     0  57 41 4C 31                                             header
     4  01 00 04 00 00 00 01 1C 06                              BEGIN txn 1
    13  02 00 0D 00 00 00 01 01 78 00 05 68 65 6C 6C 6F F8 A4   PUT txn 1, "x" = "hello"
    31  04 00 04 00 00 00 01 31 09                              COMMIT txn 1
    40  01 00 04 00 00 00 02 1D 07                              BEGIN txn 2
    49  02 00 0B 00 00 00 02 01 78 00 03 62 79 65 16 CC         PUT txn 2, "x" = "bye"
    65  04 00 04 00 00 00                                       COMMIT txn 2, cut short (6 of 9 bytes)
L = 71
```

Expected result:

```
state       = {"x"="hello"}
committed   = [1]
aborted     = []
incomplete  = [2]
validLength = 65
tornTail    = true
```

Why: at p = 65, 6 bytes remain, the type is 4 and N is 4, but 65 + 5 + 4 = 74 > 71, so there is a torn tail at 65 (R9, R12). Phase 2 replays the 5 intact records. Txn 1 commits x = "hello". Txn 2 is still open, so its PUT of "bye" is not applied.

### Example 3: checksum error in the middle

Story: one byte of the PUT record was damaged on disk (the value byte `76` "v" became `77` "w"), and another record follows it.

```
offset  bytes                                       record
     0  57 41 4C 31                                 header
     4  01 00 04 00 00 00 01 1C 06                  BEGIN txn 1
    13  02 00 09 00 00 00 01 01 6B 00 01 77 A3 EF   PUT txn 1, "k" = "w" (damaged)
    27  04 00 04 00 00 00 01 31 09                  COMMIT txn 1
L = 36
```

Expected outcome: `recover` throws a `WalException` with `code()` = CHECKSUM_MISMATCH and `offset()` = 13 (message `CHECKSUM_MISMATCH@13`).

Why: the record at 4 is intact. For the record at 13, N = 9 and it ends at 27. The checksum of its bytes `02 00 09 00 00 00 01 01 6B 00 01 77` is s2 = `A4`, s1 = `F0`, but the stored bytes are `A3 EF`. The record ends before L = 36, so R10 throws CHECKSUM_MISMATCH at 13.
