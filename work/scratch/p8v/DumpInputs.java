/** v3 audit: prints id, L and hex of each test input. Never prints expected lines; no decoding. */
public final class DumpInputs {
    public static void main(String[] args) {
        java.util.List<TestCase> all = new java.util.ArrayList<>(PublicTests.cases());
        all.addAll(HiddenTests.cases());
        for (TestCase t : all) {
            if (t.input == null) { System.out.println(t.id + " NULL"); continue; }
            System.out.println(t.id + " L=" + t.input.length + " " + Hex.format(t.input));
        }
    }
}
