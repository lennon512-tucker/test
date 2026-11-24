import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Fuzz driver: one hex log per stdin line -> one canonical result line (Render) per input. Compares nothing. */
public final class FuzzMain {
    public static void main(String[] a) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            byte[] b = Hex.bytes(line);
            String r;
            try { r = Render.result(WalRecovery.recover(b)); }
            catch (WalRecovery.WalException e) { r = "ERR " + e.code() + "@" + e.offset(); }
            catch (Throwable t) { r = Render.thrown(t); }
            out.append(r).append('\n');
        }
        System.out.print(out);
    }
}
