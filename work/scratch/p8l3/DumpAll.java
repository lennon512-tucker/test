import java.util.ArrayList;
import java.util.List;

/** P8 hardening helper: prints each test input's id, length and full hex. Never decodes; never prints expected values. */
public final class DumpAll {
    public static void main(String[] a) {
        List<TestCase> all = new ArrayList<>();
        all.addAll(PublicTests.cases());
        all.addAll(HiddenTests.cases());
        for (TestCase tc : all) {
            if (tc.input == null) { System.out.println(tc.id + " null"); continue; }
            System.out.println(tc.id + " " + tc.input.length + " " + Hex.format(tc.input));
        }
    }
}
