# WAL Crash Recovery

## 1. Overview

A small key-value store writes every change to a write-ahead log (WAL) before it changes its data.
After a crash, the store is rebuilt from the log alone.
Your job is to write `WalRecovery.recover(byte[] log)`, which reads such a log, checks it, and returns the recovered state.
Recovery has two phases: phase 1 checks the header and the frame of every record to find where the valid log ends, and phase 2 replays the intact records, checks their contents, and applies the committed transactions in commit order.
A transaction can set named savepoints and roll back to them, and it can hold at most 8 writes at a time.
Checkpoint records list the transactions that are open at that point in the log, and recovery checks that each list is right.
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
        DUPLICATE_TXN, TOO_MANY_OPEN, TXN_NOT_OPEN,
        TXN_TOO_LARGE, BAD_NAME, NO_SAVEPOINT,
        CHECKPOINT_MISMATCH
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
- Each open transaction has a write list (R20, R21) and a set of savepoints (R23a). A write is one entry of a write list: one PUT or one DELETE. A savepoint has a name (one byte), a position (a number of writes) and the SAVEPOINT record that set it (R23a).
- "The open transactions at a record" are the transactions that are open just before that record is processed in phase 2: their BEGIN record has been accepted, and their COMMIT or ABORT record (if any) has not been accepted yet.
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
| 6 | SAVEPOINT | txn (u32), name (u8) | 5 | 10 |
| 7 | ROLLBACK | txn (u32), name (u8) | 5 | 10 |
| 8 | CHECKPOINT | c (u8), then c txn ids (u32 each) | 1 + 4c | 6 + 4c |

- `txn` is the transaction id. `k` is the key length and `v` is the value length, both in bytes. `name` is the savepoint name: one byte. Two names are the same when their bytes are equal. `c` is the number of txn ids that a CHECKPOINT lists; it can be 0.
- Positions in the payload: txn is payload bytes 0 to 3 (types 1 to 7). PUT and DELETE: k is payload byte 4; the key is payload bytes 5 to 4 + k; v (PUT only) is the u16 at payload byte 5 + k; the value is payload bytes 7 + k to 6 + k + v. SAVEPOINT and ROLLBACK: name is payload byte 4. CHECKPOINT: c is payload byte 0, and listed id number i (i from 1 to c) is the u32 at payload byte 1 + 4 × (i − 1). A CHECKPOINT has no txn field of its own: it does not belong to a transaction.
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
| MAX_WRITES | 8 | most writes that one transaction's write list can hold at the same time (R19a) |
| NAME_BYTE_MIN, NAME_BYTE_MAX | `0x21`, `0x7E` | allowed savepoint name bytes: printable ASCII without space |

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

For each p, the checks are made in this order: R5, R6, R7, R8, R9, R10, R11. The first rule that applies decides what happens at p. Because of this order, R7 and R8 apply to every record with at least 3 bytes left at p, including a record that does not fit in the log (R9) and a last record whose checksum is wrong (R10).

- **R5.** If p = L, phase 1 ends with no torn tail: validLength = L and tornTail = false.
- **R6.** If L − p is 1 or 2, there is a torn tail at p (R12), whatever those bytes are.
- **R7.** If the type byte at p is not 1, 2, 3, 4, 5, 6, 7 or 8, throw UNKNOWN_TYPE at p.
- **R8.** If N (the u16 at p + 1) is greater than MAX_PAYLOAD, throw RECORD_TOO_LARGE at p. N is unsigned: for example, the bytes `80 00` are N = 32768, which is greater than MAX_PAYLOAD.
- **R9.** If p + 5 + N > L (the record does not fit in the log), there is a torn tail at p (R12).
- **R10.** If the stored checksum is wrong: when the record ends exactly at L (p + 5 + N = L), there is a torn tail at p (R12); when it ends before L (p + 5 + N < L), throw CHECKSUM_MISMATCH at p. This holds even when only 1 or 2 bytes follow the record.
- **R11.** Otherwise the record is intact. Add it to the list of intact records, set p to p + 5 + N, and continue with R5.
- **R12.** A torn tail at p ends phase 1: validLength = p and tornTail = true. The bytes from p to L − 1 are the torn tail. They hold no intact records, and phase 2 does not process or check them (P5).

### 7.3 Phase 2: replay

Phase 2 runs only when phase 1 ended without throwing (at R5 or with a torn tail). It processes the intact records one at a time, in log order. At the start, no transaction is open, no BEGIN record has been processed, and the state is an empty map.

For each record at p, the checks are made in this order: R13, R14, R15, R16, R16a, R17, R18, R19, R19a, R19b, R19c. Each check names the record types it applies to; for other types it is skipped. The first check that fails decides the error. If no check fails, the action for the record's type (R20 to R23c) is carried out, and phase 2 moves on to the next record. R13 to R16a are field checks (they look only at the record itself); R17 to R19c are transaction checks (they also look at the earlier records).

- **R13.** Payload layout (all types). The payload is well-formed when these conditions hold, tested in this order:
  - BEGIN, COMMIT, ABORT: N = 4.
  - PUT: N ≥ 5; then, with k = payload byte 4, N ≥ 7 + k; then, with v = the u16 at payload byte 5 + k, N = 7 + k + v.
  - DELETE: N ≥ 5; then, with k = payload byte 4, N = 5 + k.
  - SAVEPOINT, ROLLBACK: N = 5.
  - CHECKPOINT: N ≥ 1; then, with c = payload byte 0, N = 1 + 4 × c.

  If a condition fails, throw MALFORMED_PAYLOAD at p.
- **R14.** Txn ids (all types). For types 1 to 7: if txn (the u32 at payload byte 0) is 0, throw BAD_TXN_ID at p. For CHECKPOINT: if any of the c listed ids is 0, throw BAD_TXN_ID at p.
- **R15.** Key (PUT, DELETE). If k < MIN_KEY_LEN, or k > MAX_KEY_LEN, or any of the k key bytes is outside KEY_BYTE_MIN to KEY_BYTE_MAX, throw BAD_KEY at p.
- **R16.** Value (PUT). If any of the v value bytes is outside VALUE_BYTE_MIN to VALUE_BYTE_MAX, throw BAD_VALUE at p.
- **R16a.** Name (SAVEPOINT, ROLLBACK). If the name byte (payload byte 4) is outside NAME_BYTE_MIN to NAME_BYTE_MAX, throw BAD_NAME at p.
- **R17.** Reused id (BEGIN). If an earlier BEGIN record in the log has the same txn, throw DUPLICATE_TXN at p. This applies whether that transaction is still open, committed or aborted.
- **R18.** Open limit (BEGIN). If MAX_OPEN_TXNS transactions are open at this point (before this BEGIN record is applied), throw TOO_MANY_OPEN at p. A transaction whose COMMIT or ABORT record has already been processed is no longer open and does not count.
- **R19.** Open check (PUT, DELETE, COMMIT, ABORT, SAVEPOINT, ROLLBACK). If the transaction txn is not open, throw TXN_NOT_OPEN at p. This covers an id that never had a BEGIN record and an id whose transaction is already committed or aborted.
- **R19a.** Write limit (PUT, DELETE). If the transaction's write list already holds MAX_WRITES writes at this point (before this record is applied), throw TXN_TOO_LARGE at p. Only the writes that are in the write list at this point count. Writes that a ROLLBACK has removed (R23b) no longer count, so a ROLLBACK that removes writes frees room for new writes. Every PUT and every DELETE in the list counts as one write, also when it writes the same key as another write. SAVEPOINT, ROLLBACK and CHECKPOINT records are not writes and never count.
- **R19b.** Savepoint check (ROLLBACK). If the transaction has no savepoint with this name at this point, throw NO_SAVEPOINT at p. This covers a name that no SAVEPOINT record of this transaction has set, and a savepoint that an earlier ROLLBACK removed (R23b) and that no SAVEPOINT record has created again since then. Each transaction has its own savepoints: a savepoint of another transaction with the same name does not count.
- **R19c.** Checkpoint check (CHECKPOINT). Let m be the number of open transactions at this record, and let o_1, o_2, …, o_m be their txn ids in ascending numeric order of the u32 values. The checkpoint is right when c = m and, for each i from 1 to c, listed id number i equals o_i. Otherwise throw CHECKPOINT_MISMATCH at p. So the list must hold every open txn id exactly once, no other id, in ascending order: a list in another order, a list with a repeated id, a list that leaves out an open id, and a list with an id that is not open (no BEGIN record for it has been processed yet, or it is already committed or aborted) are all wrong. R19 does not apply to CHECKPOINT records.
- **R20.** BEGIN action: the transaction txn becomes open, with an empty write list and no savepoints.
- **R21.** PUT and DELETE action: append the write (PUT of key and value, or DELETE of key) to the end of the transaction's write list. The state does not change yet.
- **R22.** COMMIT action: apply the transaction's writes to the state one by one, in write-list order. A PUT sets the key to the value, adding the key or replacing its old value. A DELETE removes the key; if the key is not in the state, it does nothing. Then append txn to `committed`; the transaction is no longer open, and its savepoints are discarded.
- **R23.** ABORT action: discard the transaction's writes without applying them. Append txn to `aborted`; the transaction is no longer open, and its savepoints are discarded.
- **R23a.** SAVEPOINT action: let n be the number of writes in the transaction's write list. If the transaction has no savepoint with this name, create one with position n. If it already has one, move it: its position becomes n. In both cases, this SAVEPOINT record is now the record that set the savepoint; R23b compares the offsets of these records. So a savepoint is set by the SAVEPOINT record that created it or that moved it most recently. Moving a savepoint does not change or remove any other savepoint. The write list and the state do not change.
- **R23b.** ROLLBACK action: let s be the transaction's savepoint with this name (it exists, R19b) and n its position. The write list keeps its first n writes and loses all writes after them; these are the writes added after s was set. (The write list always holds at least n writes at this point.) Writes removed this way are never applied. Then remove every other savepoint of the transaction that was set by a SAVEPOINT record at a larger offset than the record that set s. Only these offsets decide, not the positions: another savepoint with the same position as s is removed when its setting record is at a larger offset, and it stays when its setting record is at a smaller offset. The savepoint s itself stays, with the same position n, so a later ROLLBACK can name it again. The transaction stays open, and the state does not change.
- **R23c.** CHECKPOINT action: nothing changes. No transaction opens or closes, and no write list, savepoint or state changes.

### 7.4 Result

If phase 2 processes every intact record without throwing, `recover` returns a `Result` with the fields below. No field and no list element is null. Each txn id in the lists is its u32 value as a non-negative `long`: for example, the txn bytes `FF FF FF FF` give 4294967295, not −1.

- **R24.** `state`: the state after phase 2. It contains only the effects of committed transactions. The tests do not check the map's iteration order.
- **R25.** `committed`: the txn of each COMMIT record processed by phase 2, in log order. `aborted`: the txn of each ABORT record processed by phase 2, in log order. A COMMIT or ABORT in the torn tail is not processed, so it is not listed. SAVEPOINT, ROLLBACK and CHECKPOINT records add nothing to these lists.
- **R26.** `incomplete`: the txn of every transaction that is still open after phase 2, in ascending numeric order of the u32 values.
- **R27.** `validLength` and `tornTail`: the values set by phase 1 (R5 or R12).

The tests compare `state` as a set of key-value pairs, and compare each list element by element, in order.

## 8. Error precedence

`recover` throws at most one exception: the first one reached by the rules in section 7. These consequences of the rule order are stated here so that no doubt is left:

- **P1.** The header rules R1 to R4 come first. If one of them decides the outcome, no record is read.
- **P2.** Phase 1 finishes before phase 2 starts. A phase-1 error anywhere in the log beats a phase-2 error anywhere in the log, even when the phase-2 problem is in an earlier record. The phase-1 errors are UNKNOWN_TYPE, RECORD_TOO_LARGE and CHECKSUM_MISMATCH. The phase-2 errors are MALFORMED_PAYLOAD, BAD_TXN_ID, BAD_KEY, BAD_VALUE, BAD_NAME, DUPLICATE_TXN, TOO_MANY_OPEN, TXN_NOT_OPEN, TXN_TOO_LARGE, NO_SAVEPOINT and CHECKPOINT_MISMATCH.
- **P3.** Phase 1 stops at the first p, in log order, where it throws or finds a torn tail. Nothing after that point is checked by phase 1.
- **P4.** In phase 2, the first record in log order that fails a check decides the error. Within one record, the first failing check in the order R13, R14, R15, R16, R16a, R17, R18, R19, R19a, R19b, R19c decides the code. So the field checks (R13 to R16a) always come before the transaction checks (R17 to R19c). For a BEGIN record, DUPLICATE_TXN (R17) comes before TOO_MANY_OPEN (R18). For a PUT or DELETE record, TXN_NOT_OPEN (R19) comes before TXN_TOO_LARGE (R19a). For a ROLLBACK record, TXN_NOT_OPEN (R19) comes before NO_SAVEPOINT (R19b). For a SAVEPOINT or ROLLBACK record, BAD_NAME (R16a) comes before TXN_NOT_OPEN (R19). For a CHECKPOINT record, MALFORMED_PAYLOAD (R13) comes before BAD_TXN_ID (R14), and BAD_TXN_ID (R14) comes before CHECKPOINT_MISMATCH (R19c).
- **P5.** Bytes in the torn tail are never checked by phase 2, whatever they contain. A torn tail does not prevent errors: if an intact record before the torn tail fails a phase-2 check, `recover` throws that error.

## 9. Error table

The offset p is the offset of the type byte of the record that fails.

This table is a summary of sections 7 and 8. A row applies only when no earlier check in their order has already decided the outcome. For example, a PUT record with txn 0 whose N does not match its layout gives MALFORMED_PAYLOAD (R13), not BAD_TXN_ID (R14).

| Outcome | When | Rule | Offset reported |
|---|---|---|---|
| `NullPointerException` | `log` is null | R1 | none |
| BAD_MAGIC | L is 1 to 3 and the bytes are not the first L bytes of MAGIC; or L ≥ 4 and bytes 0 to 3 are not MAGIC | R3, R4 | 0 |
| UNKNOWN_TYPE | at least 3 bytes remain at p and the type byte is not 1 to 8 | R7 | p |
| RECORD_TOO_LARGE | at least 3 bytes remain at p, the type byte is 1 to 8, and N > 1024 | R8 | p |
| CHECKSUM_MISMATCH | the record fits, its stored checksum is wrong, and it ends before L | R10 | p |
| MALFORMED_PAYLOAD | N does not match the payload layout of the record type | R13 | p |
| BAD_TXN_ID | txn is 0, or a CHECKPOINT lists the id 0 | R14 | p |
| BAD_KEY | k is 0, k is more than 32, or a key byte is outside `0x21` to `0x7E` | R15 | p |
| BAD_VALUE | a value byte is outside `0x20` to `0x7E` | R16 | p |
| BAD_NAME | SAVEPOINT or ROLLBACK whose name byte is outside `0x21` to `0x7E` | R16a | p |
| DUPLICATE_TXN | BEGIN with a txn used by an earlier BEGIN record | R17 | p |
| TOO_MANY_OPEN | BEGIN while 4 transactions are open | R18 | p |
| TXN_NOT_OPEN | PUT, DELETE, COMMIT, ABORT, SAVEPOINT or ROLLBACK for a transaction that is not open | R19 | p |
| TXN_TOO_LARGE | PUT or DELETE while the transaction's write list holds 8 writes | R19a | p |
| NO_SAVEPOINT | ROLLBACK with a name for which the transaction has no savepoint | R19b | p |
| CHECKPOINT_MISMATCH | CHECKPOINT whose list is not exactly the open txn ids in ascending order | R19c | p |

## 10. Not errors

These cases are valid and must not throw:

1. A DELETE of a key that is not in the state when its transaction commits. The DELETE does nothing (R22).
2. A PUT with an empty value (v = 0). It sets the key to `""` when its transaction commits.
3. A COMMIT of a transaction with no writes. Its txn is appended to `committed`, and the state does not change.
4. BEGIN records with txn ids in any order. Ids do not need to increase or be consecutive; any id from 1 to 4294967295 is valid. (A CHECKPOINT still lists its ids in ascending order, R19c.)
5. A log of exactly the 4 header bytes. The result is the empty result with validLength = 4 and tornTail = false (R5).
6. 1 or 2 bytes left after the header or after the last intact record. They are a torn tail (R6): validLength is the offset of the first of those bytes, and tornTail = true.
7. A transaction that is never committed or aborted. Its txn is in `incomplete`, and its writes are never applied.
8. Several transactions that write the same key, or one transaction that writes the same key more than once. The writes are applied at commit time in the order of R22, so the write applied last decides the final value.
9. A torn tail whose bytes would break a rule if they were checked (P5).
10. A transaction whose write list holds exactly MAX_WRITES writes. Also a PUT or DELETE in a transaction that has had more than MAX_WRITES PUT and DELETE records in total, when ROLLBACK records have removed writes so that the write list holds fewer than MAX_WRITES writes just before this record: only the writes in the write list count (R19a).
11. A SAVEPOINT or ROLLBACK record while the write list holds MAX_WRITES writes. They are not writes (R19a).
12. A SAVEPOINT with a name for which the transaction already has a savepoint. It moves that savepoint (R23a).
13. A ROLLBACK that removes no writes. Also a ROLLBACK that names the same savepoint as an earlier ROLLBACK: a ROLLBACK does not remove the savepoint it names (R23b). This holds only while the savepoint still exists; if a ROLLBACK in between removed it, R19b applies.
14. The same savepoint name in two different transactions. Each transaction has its own savepoints: a SAVEPOINT or ROLLBACK of one transaction never creates, moves, removes or uses a savepoint of another transaction (R19b, R23a, R23b).
15. A savepoint that no ROLLBACK ever names. It has no effect on the result.
16. A CHECKPOINT with c = 0 while no transaction is open. Also several CHECKPOINT records in one log, a CHECKPOINT as the first record, and a CHECKPOINT while open transactions have writes or savepoints, in each case when the list is right (R19c, R23c).
17. A right list that names a transaction which commits or aborts later in the log, or that leaves out a transaction whose BEGIN record comes later. Only the open transactions at the CHECKPOINT record count (R19c).

## 11. Worked examples

Bytes are in hex. The first line is the header; each further line is one record: its offset (decimal), its bytes, and what it is. The last line gives L. In the expected results, `{"a"="1"}` is a map in which the key "a" has the value "1", and `[2, 1]` is a list with the elements 2 and 1, in that order.

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

### Example 4: savepoint and rollback

Story: transaction 1 writes "a", sets the savepoint named `73` ("s"), writes "b" and deletes "a", rolls back to the savepoint, writes "c" and commits.

```
offset  bytes                                       record
     0  57 41 4C 31                                 header
     4  01 00 04 00 00 00 01 1C 06                  BEGIN txn 1
    13  02 00 09 00 00 00 01 01 61 00 01 31 36 A0   PUT txn 1, "a" = "1"
    27  06 00 05 00 00 00 01 73 C3 7F               SAVEPOINT txn 1, name 0x73 ("s")
    37  02 00 09 00 00 00 01 01 62 00 01 32 3B A2   PUT txn 1, "b" = "2"
    51  03 00 06 00 00 00 01 01 61 AB 6C            DELETE txn 1, "a"
    62  07 00 05 00 00 00 01 73 CB 80               ROLLBACK txn 1, name 0x73 ("s")
    72  02 00 09 00 00 00 01 01 63 00 01 33 40 A4   PUT txn 1, "c" = "3"
    86  04 00 04 00 00 00 01 31 09                  COMMIT txn 1
L = 95
```

The SAVEPOINT at 27, split into fields: type `06`, N `00 05`, txn `00 00 00 01`, name `73`, checksum `C3 7F`.

Expected result:

```
state       = {"a"="1", "c"="3"}
committed   = [1]
aborted     = []
incomplete  = []
validLength = 95
tornTail    = false
```

Why: all 8 records are intact, and phase 1 ends at p = 95 = L (R5). The SAVEPOINT at 27 creates the savepoint `73` with position 1, because the write list then holds one write, PUT a = "1" (R23a). The ROLLBACK at 62 keeps that first write and removes the PUT of "b" and the DELETE of "a" (R23b); txn 1 stays open. The PUT at 72 appends c = "3", so the COMMIT at 86 applies PUT a = "1" and then PUT c = "3" (R22).

### Example 5: checkpoints

Story: transactions 2 and 1 begin, a checkpoint lists both, transaction 1 commits, and a second checkpoint lists the one transaction that is still open.

```
offset  bytes                                       record
     0  57 41 4C 31                                 header
     4  01 00 04 00 00 00 02 1D 07                  BEGIN txn 2
    13  01 00 04 00 00 00 01 1C 06                  BEGIN txn 1
    22  02 00 09 00 00 00 01 01 61 00 01 31 36 A0   PUT txn 1, "a" = "1"
    36  08 00 09 02 00 00 00 01 00 00 00 02 D3 16   CHECKPOINT, c = 2: txn 1, txn 2
    50  04 00 04 00 00 00 01 31 09                  COMMIT txn 1
    59  08 00 05 01 00 00 00 02 65 10               CHECKPOINT, c = 1: txn 2
L = 69
```

The CHECKPOINT at 36, split into fields: type `08`, N `00 09`, c `02`, listed ids `00 00 00 01` and `00 00 00 02`, checksum `D3 16`.

Expected result:

```
state       = {"a"="1"}
committed   = [1]
aborted     = []
incomplete  = [2]
validLength = 69
tornTail    = false
```

Why: all 6 records are intact, and phase 1 ends at p = 69 = L (R5). At the CHECKPOINT at 36, the open transactions are 2 and 1; in ascending order they are 1, 2, which is the listed order, so R19c passes, and the CHECKPOINT changes nothing (R23c). The COMMIT at 50 applies a = "1". At the CHECKPOINT at 59, only txn 2 is open, and the list is 2, so R19c passes again. Txn 2 is still open at the end, so it is in `incomplete`.
