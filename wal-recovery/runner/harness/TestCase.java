/** One test: an input log (or null) and the expected canonical line. */
public final class TestCase {
    public final String id;
    public final String name;
    public final String rules;
    public final byte[] input;     // null for a null-input test
    public final String expected;

    private TestCase(String id, String name, String rules, byte[] input, String expected) {
        this.id = id;
        this.name = name;
        this.rules = rules;
        this.input = input == null ? null : input.clone();
        this.expected = expected;
    }

    public static TestCase of(String id, String name, String rules, byte[] input, String expected) {
        if (input == null) throw new IllegalArgumentException("use ofNull for a null input");
        return new TestCase(id, name, rules, input, expected);
    }

    public static TestCase ofNull(String id, String name, String rules, String expected) {
        return new TestCase(id, name, rules, null, expected);
    }
}
