import java.util.*;
/** Frame walk by N only (no checks, no outcome): id, L, record count, offsets:types (all, or last 2). */
public final class Frames {
    public static void main(String[] a) {
        Set<String> want = new HashSet<>(Arrays.asList(a));
        for (TestCase tc : HiddenTests.cases()) {
            if (!want.isEmpty() && !want.contains(tc.id)) continue;
            byte[] b = tc.input; int p = 4; List<String> r = new ArrayList<>();
            while (p + 3 <= b.length) { int n = ((b[p+1]&0xFF)<<8)|(b[p+2]&0xFF); r.add(p + ":" + (b[p]&0xFF)); p += 5 + n; }
            String s = r.size() <= 14 ? String.join(" ", r) : (r.size() + " records ... " + r.get(r.size()-2) + " " + r.get(r.size()-1));
            System.out.println(tc.id + " L=" + b.length + " " + s);
        }
    }
}
