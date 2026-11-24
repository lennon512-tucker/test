import java.nio.file.*;
public final class TimeMain {
    public static void main(String[] a) throws Exception {
        byte[] b = Files.readAllBytes(Path.of(a[0]));
        long t0 = System.nanoTime(); String r;
        try { WalRecovery.Result x = WalRecovery.recover(b); r = "OK committed=" + x.committed().size() + " aborted=" + x.aborted().size(); }
        catch (WalRecovery.WalException e) { r = "ERR " + e.code() + "@" + e.offset(); }
        catch (Throwable t) { r = "THROW " + t.getClass().getName(); }
        System.out.printf("%s %.2fs%n", r, (System.nanoTime() - t0) / 1e9);
    }
}
