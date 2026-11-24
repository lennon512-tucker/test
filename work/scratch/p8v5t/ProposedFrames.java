import java.util.*;
/** Frame walk by N only for the proposed tests (offsets:types and L; no checks, no outcome). */
public final class ProposedFrames {
    static void walk(String id, byte[] b) {
        int p = 4; List<String> r = new ArrayList<>();
        while (p + 3 <= b.length) { int n = ((b[p+1]&0xFF)<<8)|(b[p+2]&0xFF); r.add(p + ":" + (b[p]&0xFF)); p += 5 + n; }
        System.out.println(id + " L=" + b.length + " " + String.join(" ", r));
    }
    public static void main(String[] a) {
        walk("X1", LogBuilder.wal().begin(2).put(2, "a", "2").begin(1).readAbsent(1, "a").commit(2).commit(1).build());
        walk("X2", LogBuilder.wal().begin(1).put(1, "a", "").commit(1).begin(2).read(2, "a", "").readAbsent(2, "a").commit(2).build());
        walk("X3", LogBuilder.wal().begin(1).put(1, "a", "1").put(1, "a", "2").del(1, "a").put(1, "a", "3").put(1, "b", "1")
                .put(1, "b", "2").put(1, "c", "1").read(1, "a", "3").range(1, "a", "c", 3).del(1, "c")
                .range(1, "a", "c", 2).readAbsent(1, "c").commit(1).build());
        walk("X4", LogBuilder.wal().begin(1).begin(2).readAbsent(1, "a").put(1, "b", "1").put(2, "b", "2").commit(2).commit(1).build());
        walk("X5", LogBuilder.wal().begin(1).begin(2).begin(3).range(1, "a", "c", 0).put(2, "b", "1").commit(2).commit(1)
                .checkpoint(3).commit(3).build());
        walk("X6", LogBuilder.wal().begin(1).begin(2).savepoint(1, 0x73).range(1, "a", "c", 0).rollback(1, 0x73)
                .put(2, "b", "1").commit(2).commit(1).build());
    }
}
