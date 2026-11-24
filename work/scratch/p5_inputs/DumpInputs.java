// P5 helper: prints length and hex of each hidden-test INPUT (no decoding, no expected values).
public class DumpInputs {
    public static void main(String[] a) {
        for (TestCase t : HiddenTests.cases()) {
            byte[] b = t.input;
            String h = b.length <= 80 ? Hex.format(b) : "(long)";
            System.out.println(t.id + " L=" + b.length + " " + h);
        }
    }
}
