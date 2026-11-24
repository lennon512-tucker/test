import java.util.ArrayList;
import java.util.List;

/** P7 audit helper: prints each test input's id, length and hex. Never decodes; never prints expected values. */
public final class DumpInputs {
    public static void main(String[] a) {
        List<TestCase> all = new ArrayList<>();
        all.addAll(PublicTests.cases());
        all.addAll(HiddenTests.cases());
        for (TestCase tc : all) {
            if (tc.input == null) { System.out.println(tc.id + " L=null"); continue; }
            byte[] b = tc.input;
            String hex;
            if (b.length <= 140) hex = Hex.format(b);
            else {
                byte[] head = java.util.Arrays.copyOfRange(b, 0, 40);
                byte[] tail = java.util.Arrays.copyOfRange(b, b.length - 40, b.length);
                hex = Hex.format(head) + " ... " + Hex.format(tail);
            }
            System.out.println(tc.id + " L=" + b.length + " " + hex);
        }
    }
}
