import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Usage: java TestMain public|hidden          runs the tests, one @@TEST line each
 *        java TestMain public|hidden only ID  runs only the test ID (used by javatest.py to
 *                                             re-run tests after a TIMEOUT in a fresh JVM)
 *        java TestMain list public|hidden     lists the tests (@@CASE lines), runs nothing
 */
public final class TestMain {
    private static final long LIMIT_MS = 5000;

    private static final class Outcome {
        volatile boolean done;
        volatile WalRecovery.Result result;
        volatile Throwable thrown;
        volatile boolean returned;
    }

    public static void main(String[] args) {
        boolean list = args.length == 2 && args[0].equals("list");
        String only = args.length == 3 && args[1].equals("only") ? args[2] : null;
        String suite = list ? args[1] : (args.length == 1 || only != null ? args[0] : "");
        List<TestCase> cases;
        if (suite.equals("public")) cases = PublicTests.cases();
        else if (suite.equals("hidden")) cases = HiddenTests.cases();
        else {
            System.err.println("usage: java TestMain [list] public|hidden");
            System.exit(2);
            return;
        }

        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        // Whatever the solution prints must not disturb the @@ lines.
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        System.setOut(sink);
        System.setErr(sink);

        for (TestCase tc : cases) {
            if (only != null && !tc.id.equals(only)) continue;
            if (list) {
                out.print("@@CASE\t" + esc(tc.id) + "\t" + esc(tc.name) + "\t" + esc(tc.rules)
                        + "\t" + esc(tc.expected) + "\n");
                continue;
            }
            String actual;
            try {
                actual = runOne(tc);
            } catch (Throwable t) {
                actual = "HARNESS_ERROR " + t.getClass().getName();
            }
            boolean pass = actual.equals(tc.expected);
            out.print("@@TEST\t" + esc(tc.id) + "\t" + (pass ? "PASS" : "FAIL") + "\t"
                    + esc(tc.expected) + "\t" + esc(actual) + "\n");
            out.flush();
        }
        out.flush();
        System.exit(0);
    }

    private static String runOne(TestCase tc) throws InterruptedException {
        final byte[] original = tc.input == null ? null : tc.input.clone();
        final byte[] given = tc.input == null ? null : tc.input.clone();
        final Outcome o = new Outcome();
        Thread t = new Thread(() -> {
            try {
                o.result = WalRecovery.recover(given);
                o.returned = true;
            } catch (Throwable e) {
                o.thrown = e;
            } finally {
                o.done = true;
            }
        }, "recover");
        t.setDaemon(true);
        t.start();
        t.join(LIMIT_MS);
        if (!o.done) return "TIMEOUT";

        String line;
        try {
            line = o.returned ? Render.result(o.result) : Render.thrown(o.thrown);
        } catch (Throwable e) {
            line = "RENDER_ERROR " + e.getClass().getName();
        }
        if (original != null && !Arrays.equals(original, given)) line += " +INPUT_MODIFIED";
        return line;
    }

    /** Escapes backslash, tab, newline and carriage return. */
    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\t': sb.append("\\t"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
