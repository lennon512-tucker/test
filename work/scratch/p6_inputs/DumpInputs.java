// P6 helper: prints length and hex of each hidden-test INPUT from H25 on (no decoding, no expected values).
public class DumpInputs {
    public static void main(String[] a) {
        for (TestCase t : HiddenTests.cases()) {
            if (t.id.compareTo("H25") < 0) continue;
            byte[] b = t.input;
            if (b == null) { System.out.println(t.id + " null"); continue; }
            String h = b.length <= 140 ? Hex.format(b) : "(long)";
            System.out.println(t.id + " L=" + b.length + " " + h);
        }
    }
}
