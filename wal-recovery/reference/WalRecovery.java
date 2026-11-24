// File: WalRecovery.java (no package declaration, Java 21, standard library only)
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class WalRecovery {
    private WalRecovery() {}

    // ---- Limits (section 6) ----
    private static final byte[] MAGIC = {0x57, 0x41, 0x4C, 0x31};
    private static final int HEADER_SIZE = 4;
    private static final int MAX_PAYLOAD = 1024;
    private static final int MIN_KEY_LEN = 1;
    private static final int MAX_KEY_LEN = 32;
    private static final int KEY_BYTE_MIN = 0x21, KEY_BYTE_MAX = 0x7E;
    private static final int VALUE_BYTE_MIN = 0x20, VALUE_BYTE_MAX = 0x7E;
    private static final int MAX_OPEN_TXNS = 4;
    private static final int MAX_WRITES = 8;
    private static final int NAME_BYTE_MIN = 0x21, NAME_BYTE_MAX = 0x7E;

    // ---- Record types (section 5.3) ----
    private static final int T_BEGIN = 1, T_PUT = 2, T_DELETE = 3, T_COMMIT = 4, T_ABORT = 5,
            T_SAVEPOINT = 6, T_ROLLBACK = 7, T_CHECKPOINT = 8, T_READ = 9, T_RANGE = 10;

    /** Recovers the store from a log. Must not modify {@code log}. */
    public static Result recover(byte[] log) throws WalException {
        // R1
        if (log == null) throw new NullPointerException("log");
        final int L = log.length;
        // R2
        if (L == 0) return emptyResult(0, false);
        // R3
        if (L < 4) {
            for (int i = 0; i < L; i++) {
                if (log[i] != MAGIC[i]) throw new WalException(Code.BAD_MAGIC, 0);
            }
            return emptyResult(0, true);
        }
        // R4
        for (int i = 0; i < 4; i++) {
            if (log[i] != MAGIC[i]) throw new WalException(Code.BAD_MAGIC, 0);
        }

        // ---- Phase 1: frames (R5 to R12) ----
        IntList intact = new IntList(64);
        int p = HEADER_SIZE;
        int validLength;
        boolean tornTail;
        while (true) {
            // R5
            if (p == L) { validLength = L; tornTail = false; break; }
            // R6
            if (L - p <= 2) { validLength = p; tornTail = true; break; }
            // R7
            int type = log[p] & 0xFF;
            if (type < 1 || type > 10) throw new WalException(Code.UNKNOWN_TYPE, p);
            // R8
            int n = ((log[p + 1] & 0xFF) << 8) | (log[p + 2] & 0xFF);
            if (n > MAX_PAYLOAD) throw new WalException(Code.RECORD_TOO_LARGE, p);
            // R9
            long end = (long) p + 5 + n;
            if (end > L) { validLength = p; tornTail = true; break; }
            // R10
            int s1 = 0, s2 = 0;
            int last = p + 3 + n; // exclusive
            for (int i = p; i < last; i++) {
                s1 += log[i] & 0xFF;
                if (s1 >= 255) s1 -= 255;
                s2 += s1;
                if (s2 >= 255) s2 -= 255;
            }
            boolean ok = (log[last] & 0xFF) == s2 && (log[last + 1] & 0xFF) == s1;
            if (!ok) {
                if (end == L) { validLength = p; tornTail = true; break; }
                throw new WalException(Code.CHECKSUM_MISMATCH, p);
            }
            // R11
            intact.add(p);
            p = (int) end;
        }

        // ---- Phase 2: replay (R13 to R23e) ----
        Replay r = new Replay(log, intact);
        r.run();
        return r.result(validLength, tornTail);
    }

    private static Result emptyResult(int validLength, boolean tornTail) {
        return new Result(new HashMap<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                validLength, tornTail);
    }

    // =====================================================================================
    // Phase 2
    // =====================================================================================

    /** Sentinel for "absent" in snapshot diff maps (identity-compared). */
    private static final String ABSENT = new String("\u0000<absent>");

    private static final class Replay {
        final byte[] log;
        final IntList intact;

        // Key universe: every key of every intact, well-laid-out PUT/DELETE record, sorted in key order.
        String[] keys;          // rank -> key
        HashMap<String, Integer> rankOf;
        int U;
        int[] recRank;          // intact-record index -> key rank (PUT/DELETE with well-formed layout), else -1
        int curIdx;             // index of the record being processed

        // Current state, indexed by key rank (null = absent).
        String[] cur;
        Fenwick curFen;         // presence count of current state

        final HashSet<Long> begun = new HashSet<>();
        final Txn[] open = new Txn[MAX_OPEN_TXNS]; // indexed by slot
        int openCount = 0;
        final Slot[] slots = new Slot[MAX_OPEN_TXNS];

        final ArrayList<Long> committed = new ArrayList<>();
        final ArrayList<Long> aborted = new ArrayList<>();

        Replay(byte[] log, IntList intact) {
            this.log = log;
            this.intact = intact;
        }

        int u8(int i) { return log[i] & 0xFF; }
        int u16(int i) { return ((log[i] & 0xFF) << 8) | (log[i + 1] & 0xFF); }
        long u32(int i) {
            return ((long) (log[i] & 0xFF) << 24) | ((log[i + 1] & 0xFF) << 16)
                    | ((log[i + 2] & 0xFF) << 8) | (log[i + 3] & 0xFF);
        }
        String str(int off, int len) { return new String(log, off, len, StandardCharsets.ISO_8859_1); }

        // ---------- key universe ----------
        void buildUniverse() {
            HashMap<String, Integer> m = new HashMap<>();
            recRank = new int[intact.size];
            Arrays.fill(recRank, -1);
            for (int idx = 0; idx < intact.size; idx++) {
                int p = intact.a[idx];
                int type = u8(p);
                if (type != T_PUT && type != T_DELETE) continue;
                int n = u16(p + 1);
                int b = p + 3;
                if (n < 5) continue;
                int k = u8(b + 4);
                if (type == T_PUT) {
                    if (n < 7 + k) continue;
                    int v = u16(b + 5 + k);
                    if (n != 7 + k + v) continue;
                } else {
                    if (n != 5 + k) continue;
                }
                String key = str(b + 5, k);
                Integer id = m.get(key);
                if (id == null) { id = m.size(); m.put(key, id); }
                recRank[idx] = id; // temporary id, replaced by the rank below
            }
            keys = m.keySet().toArray(new String[0]);
            Arrays.sort(keys); // String.compareTo == key order for one char per byte
            U = keys.length;
            int[] idToRank = new int[U];
            for (int i = 0; i < U; i++) {
                idToRank[m.get(keys[i])] = i;
                m.put(keys[i], i);
            }
            for (int idx = 0; idx < intact.size; idx++) if (recRank[idx] >= 0) recRank[idx] = idToRank[recRank[idx]];
            rankOf = m;
            cur = new String[U];
            curFen = new Fenwick(U);
        }

        /** First rank whose key is >= s. */
        int lowerBound(String s) {
            int lo = 0, hi = U;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (keys[mid].compareTo(s) < 0) lo = mid + 1; else hi = mid;
            }
            return lo;
        }

        /** First rank whose key is > s. */
        int upperBound(String s) {
            int lo = 0, hi = U;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (keys[mid].compareTo(s) <= 0) lo = mid + 1; else hi = mid;
            }
            return lo;
        }

        Txn findOpen(long id) {
            for (int s = 0; s < MAX_OPEN_TXNS; s++) {
                Txn t = open[s];
                if (t != null && t.id == id) return t;
            }
            return null;
        }

        /** Snapshot value of key rank r for txn t: a String, or ABSENT. */
        String snapshotValue(Txn t, int r) {
            String d = slots[t.slot].snap[r];
            if (d != null) return d;
            String c = cur[r];
            return c == null ? ABSENT : c;
        }

        /** View of key rank r (R19d): a String, or ABSENT. r may be -1 (key not in universe). */
        String viewValue(Txn t, int r) {
            if (r < 0) return ABSENT;
            for (int i = t.wn - 1; i >= 0; i--) {
                if (t.wKey[i] == r) return t.wPut[i] ? t.wVal[i] : ABSENT;
            }
            return snapshotValue(t, r);
        }

        // ---------- field-check helpers ----------
        void checkKey(int off, int k, int p) throws WalException {
            if (k < MIN_KEY_LEN || k > MAX_KEY_LEN) throw new WalException(Code.BAD_KEY, p);
            for (int i = off; i < off + k; i++) {
                int c = log[i] & 0xFF;
                if (c < KEY_BYTE_MIN || c > KEY_BYTE_MAX) throw new WalException(Code.BAD_KEY, p);
            }
        }

        void checkValue(int off, int v, int p) throws WalException {
            for (int i = off; i < off + v; i++) {
                int c = log[i] & 0xFF;
                if (c < VALUE_BYTE_MIN || c > VALUE_BYTE_MAX) throw new WalException(Code.BAD_VALUE, p);
            }
        }

        static WalException malformed(int p) { return new WalException(Code.MALFORMED_PAYLOAD, p); }

        // ---------- main loop ----------
        void run() throws WalException {
            buildUniverse();
            for (int idx = 0; idx < intact.size; idx++) {
                int p = intact.a[idx];
                int type = u8(p);
                int n = u16(p + 1);
                int b = p + 3; // payload byte 0
                curIdx = idx;
                switch (type) {
                    case T_BEGIN -> doBegin(p, n, b);
                    case T_PUT -> doPut(p, n, b);
                    case T_DELETE -> doDelete(p, n, b);
                    case T_COMMIT -> doCommit(p, n, b);
                    case T_ABORT -> doAbort(p, n, b);
                    case T_SAVEPOINT -> doSavepoint(p, n, b);
                    case T_ROLLBACK -> doRollback(p, n, b);
                    case T_CHECKPOINT -> doCheckpoint(p, n, b);
                    case T_READ -> doRead(p, n, b);
                    case T_RANGE -> doRange(p, n, b);
                    default -> throw new IllegalStateException(); // unreachable: phase 1 checked type
                }
            }
        }

        void doBegin(int p, int n, int b) throws WalException {
            // R13
            if (n != 4) throw malformed(p);
            // R14
            long txn = u32(b);
            if (txn == 0) throw new WalException(Code.BAD_TXN_ID, p);
            // R17
            if (begun.contains(txn)) throw new WalException(Code.DUPLICATE_TXN, p);
            // R18
            if (openCount >= MAX_OPEN_TXNS) throw new WalException(Code.TOO_MANY_OPEN, p);
            // R20
            begun.add(txn);
            int slot = -1;
            for (int s = 0; s < MAX_OPEN_TXNS; s++) if (open[s] == null) { slot = s; break; }
            if (slots[slot] == null) slots[slot] = new Slot(U);
            else slots[slot].reset();
            Txn t = new Txn(txn, slot);
            open[slot] = t;
            openCount++;
        }

        void doPut(int p, int n, int b) throws WalException {
            // R13
            if (n < 5) throw malformed(p);
            int k = u8(b + 4);
            if (n < 7 + k) throw malformed(p);
            int v = u16(b + 5 + k);
            if (n != 7 + k + v) throw malformed(p);
            // R14
            long txn = u32(b);
            if (txn == 0) throw new WalException(Code.BAD_TXN_ID, p);
            // R15
            checkKey(b + 5, k, p);
            // R16
            checkValue(b + 7 + k, v, p);
            // R19
            Txn t = findOpen(txn);
            if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);
            // R19a
            if (t.wn >= MAX_WRITES) throw new WalException(Code.TXN_TOO_LARGE, p);
            // R21
            t.append(recRank[curIdx], true, str(b + 7 + k, v));
        }

        void doDelete(int p, int n, int b) throws WalException {
            // R13
            if (n < 5) throw malformed(p);
            int k = u8(b + 4);
            if (n != 5 + k) throw malformed(p);
            // R14
            long txn = u32(b);
            if (txn == 0) throw new WalException(Code.BAD_TXN_ID, p);
            // R15
            checkKey(b + 5, k, p);
            // R19
            Txn t = findOpen(txn);
            if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);
            // R19a
            if (t.wn >= MAX_WRITES) throw new WalException(Code.TXN_TOO_LARGE, p);
            // R21
            t.append(recRank[curIdx], false, null);
        }

        void doCommit(int p, int n, int b) throws WalException {
            // R13
            if (n != 4) throw malformed(p);
            // R14
            long txn = u32(b);
            if (txn == 0) throw new WalException(Code.BAD_TXN_ID, p);
            // R19
            Txn t = findOpen(txn);
            if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);
            // R22: conflict decision
            Slot s = slots[t.slot];
            boolean conflict = false;
            for (int i = 0; i < t.readRanks.size && !conflict; i++) {
                if (s.mark[t.readRanks.a[i]]) conflict = true;
            }
            for (int i = 0; i + 1 < t.ranges.size && !conflict; i += 2) {
                if (s.wfen.rangeSum(t.ranges.a[i], t.ranges.a[i + 1]) > 0) conflict = true;
            }
            close(t);
            if (conflict) {
                aborted.add(txn);
                return;
            }
            // Apply writes in write-list order.
            for (int i = 0; i < t.wn; i++) {
                int r = t.wKey[i];
                String old = cur[r];
                String nv = t.wPut[i] ? t.wVal[i] : null;
                int a = old != null ? 1 : 0;
                int c = nv != null ? 1 : 0;
                for (int os = 0; os < MAX_OPEN_TXNS; os++) {
                    if (open[os] == null) continue;
                    Slot o = slots[os];
                    // snapshot diff for point lookups
                    if (o.snap[r] == null) {
                        o.snap[r] = old == null ? ABSENT : old;
                        o.sTouched.add(r);
                    }
                    // written-keys marks for conflict detection
                    if (!o.mark[r]) {
                        o.mark[r] = true;
                        o.wfen.add(r, 1);
                        o.wTouched.add(r);
                    }
                    // snapshot presence delta (snapPresent - curPresent)
                    if (a != c) {
                        o.draw[r] += (byte) (a - c);
                        o.dfen.add(r, a - c);
                        o.dTouched.add(r);
                    }
                }
                if (a != c) curFen.add(r, c - a);
                cur[r] = nv;
            }
            committed.add(txn);
        }

        void doAbort(int p, int n, int b) throws WalException {
            // R13
            if (n != 4) throw malformed(p);
            // R14
            long txn = u32(b);
            if (txn == 0) throw new WalException(Code.BAD_TXN_ID, p);
            // R19
            Txn t = findOpen(txn);
            if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);
            // R23
            close(t);
            aborted.add(txn);
        }

        void close(Txn t) {
            open[t.slot] = null;
            openCount--;
        }

        void doSavepoint(int p, int n, int b) throws WalException {
            // R13
            if (n != 5) throw malformed(p);
            // R14
            long txn = u32(b);
            if (txn == 0) throw new WalException(Code.BAD_TXN_ID, p);
            // R16a
            int name = u8(b + 4);
            if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) throw new WalException(Code.BAD_NAME, p);
            // R19
            Txn t = findOpen(txn);
            if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);
            // R23a
            t.ensureSavepoints();
            t.spPos[name] = t.wn;
            t.spOff[name] = p;
        }

        void doRollback(int p, int n, int b) throws WalException {
            // R13
            if (n != 5) throw malformed(p);
            // R14
            long txn = u32(b);
            if (txn == 0) throw new WalException(Code.BAD_TXN_ID, p);
            // R16a
            int name = u8(b + 4);
            if (name < NAME_BYTE_MIN || name > NAME_BYTE_MAX) throw new WalException(Code.BAD_NAME, p);
            // R19
            Txn t = findOpen(txn);
            if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);
            // R19b
            if (t.spOff == null || t.spOff[name] < 0) throw new WalException(Code.NO_SAVEPOINT, p);
            // R23b
            int pos = t.spPos[name];
            int setOff = t.spOff[name];
            for (int i = pos; i < t.wn; i++) t.wVal[i] = null;
            t.wn = pos;
            for (int nm = NAME_BYTE_MIN; nm <= NAME_BYTE_MAX; nm++) {
                if (nm != name && t.spOff[nm] > setOff) t.spOff[nm] = -1;
            }
        }

        void doCheckpoint(int p, int n, int b) throws WalException {
            // R13
            if (n < 1) throw malformed(p);
            int c = u8(b);
            if (n != 1 + 4 * c) throw malformed(p);
            // R14
            long[] listed = new long[c];
            for (int i = 0; i < c; i++) {
                listed[i] = u32(b + 1 + 4 * i);
                if (listed[i] == 0) throw new WalException(Code.BAD_TXN_ID, p);
            }
            // R19c
            long[] ids = new long[openCount];
            int m = 0;
            for (int s = 0; s < MAX_OPEN_TXNS; s++) if (open[s] != null) ids[m++] = open[s].id;
            Arrays.sort(ids);
            if (c != m) throw new WalException(Code.CHECKPOINT_MISMATCH, p);
            for (int i = 0; i < c; i++) {
                if (listed[i] != ids[i]) throw new WalException(Code.CHECKPOINT_MISMATCH, p);
            }
            // R23c: nothing changes
        }

        void doRead(int p, int n, int b) throws WalException {
            // R13
            if (n < 6) throw malformed(p);
            int k = u8(b + 4);
            if (n < 6 + k) throw malformed(p);
            int f = u8(b + 5 + k);
            int v = 0;
            if (f == 0) {
                if (n != 6 + k) throw malformed(p);
            } else if (f == 1) {
                if (n < 8 + k) throw malformed(p);
                v = u16(b + 6 + k);
                if (n != 8 + k + v) throw malformed(p);
            } else {
                throw malformed(p);
            }
            // R14
            long txn = u32(b);
            if (txn == 0) throw new WalException(Code.BAD_TXN_ID, p);
            // R15
            checkKey(b + 5, k, p);
            // R16
            if (f == 1) checkValue(b + 8 + k, v, p);
            // R19
            Txn t = findOpen(txn);
            if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);
            // R19d
            Integer ri = rankOf.get(str(b + 5, k));
            int r = ri == null ? -1 : ri;
            String view = viewValue(t, r);
            boolean match;
            if (f == 0) {
                match = view == ABSENT;
            } else {
                match = view != ABSENT && view.equals(str(b + 8 + k, v));
            }
            if (!match) throw new WalException(Code.READ_MISMATCH, p);
            // R23d (a key outside the universe can never be written, so it can never conflict)
            if (r >= 0) t.readRanks.add(r);
        }

        void doRange(int p, int n, int b) throws WalException {
            // R13
            if (n < 6) throw malformed(p);
            int k1 = u8(b + 4);
            if (n < 6 + k1) throw malformed(p);
            int k2 = u8(b + 5 + k1);
            if (n != 10 + k1 + k2) throw malformed(p);
            // R14
            long txn = u32(b);
            if (txn == 0) throw new WalException(Code.BAD_TXN_ID, p);
            // R15 (lo, then hi)
            checkKey(b + 5, k1, p);
            checkKey(b + 6 + k1, k2, p);
            // R19
            Txn t = findOpen(txn);
            if (t == null) throw new WalException(Code.TXN_NOT_OPEN, p);
            // R19e
            String lo = str(b + 5, k1);
            String hi = str(b + 6 + k1, k2);
            long count = u32(b + 6 + k1 + k2);
            long cnt = 0;
            int loIdx = 0, hiIdx = -1;
            if (lo.compareTo(hi) <= 0) {
                loIdx = lowerBound(lo);
                hiIdx = upperBound(hi) - 1;
                if (loIdx <= hiIdx) {
                    Slot s = slots[t.slot];
                    cnt = (long) curFen.rangeSum(loIdx, hiIdx) + s.dfen.rangeSum(loIdx, hiIdx);
                    // Adjust for the transaction's own writes: each distinct written key once, last write decides.
                    for (int i = 0; i < t.wn; i++) {
                        int r = t.wKey[i];
                        if (r < loIdx || r > hiIdx) continue;
                        boolean lastOfKey = true;
                        for (int j = i + 1; j < t.wn; j++) if (t.wKey[j] == r) { lastOfKey = false; break; }
                        if (!lastOfKey) continue;
                        int viewPresent = t.wPut[i] ? 1 : 0;
                        int snapPresent = snapshotValue(t, r) != ABSENT ? 1 : 0;
                        cnt += viewPresent - snapPresent;
                    }
                }
            }
            if (cnt != count) throw new WalException(Code.RANGE_MISMATCH, p);
            // R23e (an empty rank interval holds no key that can ever be written)
            if (loIdx <= hiIdx) {
                t.ranges.add(loIdx);
                t.ranges.add(hiIdx);
            }
        }

        Result result(int validLength, boolean tornTail) {
            HashMap<String, String> state = new HashMap<>();
            for (int r = 0; r < U; r++) if (cur[r] != null) state.put(keys[r], cur[r]);
            long[] ids = new long[openCount];
            int m = 0;
            for (int s = 0; s < MAX_OPEN_TXNS; s++) if (open[s] != null) ids[m++] = open[s].id;
            Arrays.sort(ids);
            ArrayList<Long> incomplete = new ArrayList<>(m);
            for (long id : ids) incomplete.add(id);
            return new Result(state, committed, aborted, incomplete, validLength, tornTail);
        }
    }

    /** One open transaction. */
    private static final class Txn {
        final long id;
        final int slot;
        final int[] wKey = new int[MAX_WRITES];
        final boolean[] wPut = new boolean[MAX_WRITES];
        final String[] wVal = new String[MAX_WRITES];
        int wn = 0;
        int[] spPos; // by name byte
        int[] spOff; // offset of the SAVEPOINT record that set it; -1 = none
        final IntList readRanks = new IntList(4);
        final IntList ranges = new IntList(4); // pairs (loRank, hiRank)

        Txn(long id, int slot) { this.id = id; this.slot = slot; }

        void append(int r, boolean put, String val) {
            wKey[wn] = r;
            wPut[wn] = put;
            wVal[wn] = val;
            wn++;
        }

        void ensureSavepoints() {
            if (spOff == null) {
                spPos = new int[256];
                spOff = new int[256];
                Arrays.fill(spOff, -1);
            }
        }
    }

    /**
     * Per-open-transaction bookkeeping relative to the current state, reused across transactions.
     * snap: original (snapshot) value, or ABSENT, of every key changed by a commit since BEGIN (null = unchanged).
     * dfen/draw: snapshot presence minus current presence, per key rank.
     * mark/wfen: keys written by transactions committed since BEGIN.
     */
    private static final class Slot {
        final String[] snap;
        final IntList sTouched = new IntList(16);
        final Fenwick dfen;
        final byte[] draw;
        final IntList dTouched = new IntList(16);
        final Fenwick wfen;
        final boolean[] mark;
        final IntList wTouched = new IntList(16);

        Slot(int u) {
            snap = new String[u];
            dfen = new Fenwick(u);
            draw = new byte[u];
            wfen = new Fenwick(u);
            mark = new boolean[u];
        }

        void reset() {
            for (int i = 0; i < sTouched.size; i++) snap[sTouched.a[i]] = null;
            sTouched.size = 0;
            for (int i = 0; i < dTouched.size; i++) {
                int r = dTouched.a[i];
                if (draw[r] != 0) {
                    dfen.add(r, -draw[r]);
                    draw[r] = 0;
                }
            }
            dTouched.size = 0;
            for (int i = 0; i < wTouched.size; i++) {
                int r = wTouched.a[i];
                if (mark[r]) {
                    wfen.add(r, -1);
                    mark[r] = false;
                }
            }
            wTouched.size = 0;
        }
    }

    private static final class Fenwick {
        final int[] f;
        final int n;
        Fenwick(int n) { this.n = n; this.f = new int[n + 1]; }
        void add(int i, int d) { for (i++; i <= n; i += i & -i) f[i] += d; }
        int prefix(int i) { int s = 0; for (; i > 0; i -= i & -i) s += f[i]; return s; } // sum of [0, i)
        int rangeSum(int lo, int hi) { return prefix(hi + 1) - prefix(lo); }
    }

    private static final class IntList {
        int[] a;
        int size;
        IntList(int cap) { a = new int[Math.max(cap, 1)]; }
        void add(int v) {
            if (size == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[size++] = v;
        }
    }

    // =====================================================================================
    // Fixed API types (section 3)
    // =====================================================================================

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
        CHECKPOINT_MISMATCH, READ_MISMATCH, RANGE_MISMATCH
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
