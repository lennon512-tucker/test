import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** The canonical one-line result format (PLAN.md section 3.2). */
public final class Render {
    private Render() {}

    /** Normal result, or NULL_RESULT for a null Result. */
    public static String result(WalRecovery.Result r) {
        if (r == null) return "NULL_RESULT";
        return "OK state=" + state(r.state())
                + " committed=" + list(r.committed())
                + " aborted=" + list(r.aborted())
                + " incomplete=" + list(r.incomplete())
                + " validLength=" + r.validLength()
                + " tornTail=" + r.tornTail();
    }

    /** WalException as ERR CODE@offset; any other throwable as THROW class-name. */
    public static String thrown(Throwable t) {
        if (t instanceof WalRecovery.WalException) {
            WalRecovery.WalException e = (WalRecovery.WalException) t;
            return "ERR " + e.code() + "@" + e.offset();
        }
        return "THROW " + t.getClass().getName();
    }

    private static String state(Map<String, String> m) {
        if (m == null) return "null";
        List<Map.Entry<String, String>> es = new ArrayList<>(m.entrySet());
        es.sort(Map.Entry.comparingByKey(Comparator.nullsFirst(Comparator.<String>naturalOrder())));
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : es) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(quote(e.getKey())).append('=').append(quote(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String list(List<Long> l) {
        if (l == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object x : l) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(String.valueOf(x));
        }
        return sb.append(']').toString();
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
