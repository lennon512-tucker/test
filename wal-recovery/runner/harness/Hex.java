import java.io.ByteArrayOutputStream;

/** Parses spaced hex such as "57 41 4C 31" into bytes. */
public final class Hex {
    private Hex() {}

    public static byte[] bytes(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String tok : s.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            if (tok.length() != 2) throw new IllegalArgumentException("bad hex token: " + tok);
            out.write(Integer.parseInt(tok, 16));
        }
        return out.toByteArray();
    }

    /** Upper-case hex, bytes separated by one space. */
    public static String format(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }
}
