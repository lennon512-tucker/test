import java.io.ByteArrayOutputStream;
import java.util.*;

/** Input generator guided by one solution: builds logs record by record; for a READ it tries the
 * candidate expectations and keeps one that the guide solution does not reject at that READ, so
 * logs reach COMMIT conflict decisions. Prints hex logs. Decides no expected value. */
public final class Guide {
    static final long[] IDS = {1, 2, 3, 4, 5, 2147483648L, 4294967295L};
    static final String[] KEYS = {"a", "b", "c"};
    static final String[] VALS = {"", "1", "2"};
    static byte[] f16(byte[] d) { int s1 = 0, s2 = 0; for (byte b : d) { s1 = (s1 + (b & 0xFF)) % 255; s2 = (s2 + s1) % 255; } return new byte[]{(byte) s2, (byte) s1}; }
    static byte[] frame(int t, byte[] p) { ByteArrayOutputStream o = new ByteArrayOutputStream(); o.write(t); o.write(p.length >> 8); o.write(p.length); o.writeBytes(p); byte[] c = o.toByteArray(); o.writeBytes(f16(c)); return o.toByteArray(); }
    static byte[] u32(long v) { return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v}; }
    static byte[] cat(byte[]... a) { ByteArrayOutputStream o = new ByteArrayOutputStream(); for (byte[] x : a) o.writeBytes(x); return o.toByteArray(); }
    static byte[] put(long t, String k, String v) { return frame(2, cat(u32(t), new byte[]{(byte) k.length()}, k.getBytes(), new byte[]{0, (byte) v.length()}, v.getBytes())); }
    static byte[] read(long t, String k, String v) { return v == null ? frame(9, cat(u32(t), new byte[]{(byte) k.length()}, k.getBytes(), new byte[]{0}))
            : frame(9, cat(u32(t), new byte[]{(byte) k.length()}, k.getBytes(), new byte[]{1, 0, (byte) v.length()}, v.getBytes())); }
    static String outcome(byte[] b) { try { WalRecovery.recover(b); return "OK"; } catch (WalRecovery.WalException e) { return e.code() + "@" + e.offset(); } catch (Throwable t) { return "THROW"; } }
    public static void main(String[] a) {
        int n = Integer.parseInt(a[0]); Random R = new Random(Long.parseLong(a[1]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            byte[] log = {0x57, 0x41, 0x4C, 0x31};
            Set<Long> began = new HashSet<>(), live = new TreeSet<>();
            int steps = 6 + R.nextInt(40);
            for (int s = 0; s < steps; s++) {
                double r = R.nextDouble(); byte[] rec;
                if ((r < 0.16 && live.size() < 4) || live.isEmpty()) {
                    List<Long> c = new ArrayList<>(); for (long x : IDS) if (!began.contains(x)) c.add(x);
                    if (c.isEmpty()) break; long t = c.get(R.nextInt(c.size())); began.add(t); live.add(t); rec = frame(1, u32(t));
                } else {
                    List<Long> L = new ArrayList<>(live); long t = L.get(R.nextInt(L.size()));
                    String k = KEYS[R.nextInt(KEYS.length)];
                    if (r < 0.42) rec = put(t, k, VALS[R.nextInt(3)]);
                    else if (r < 0.50) rec = frame(3, cat(u32(t), new byte[]{1}, k.getBytes()));
                    else if (r < 0.76) {
                        String[] cand = {null, "", "1", "2"}; List<String> cs = new ArrayList<>(Arrays.asList(cand)); Collections.shuffle(cs, R);
                        rec = null; int at = log.length;
                        for (String v : cs) { byte[] t2 = cat(log, read(t, k, v)); String o = outcome(t2); if (!o.equals("READ_MISMATCH@" + at)) { rec = read(t, k, v); break; } }
                        if (rec == null) continue;
                    } else if (r < 0.88) { live.remove(t); rec = frame(4, u32(t)); }
                    else if (r < 0.91) { live.remove(t); rec = frame(5, u32(t)); }
                    else if (r < 0.96) rec = frame(6, cat(u32(t), new byte[]{0x73}));
                    else { int at = log.length; byte[] rb = frame(7, cat(u32(t), new byte[]{0x73})); if (outcome(cat(log, rb)).equals("NO_SAVEPOINT@" + at)) continue; rec = rb; }
                }
                log = cat(log, rec);
            }
            for (int j = 0; j < log.length; j++) { if (j > 0) sb.append(' '); sb.append(String.format("%02X", log[j] & 0xFF)); }
            sb.append('\n');
        }
        System.out.print(sb);
    }
}
