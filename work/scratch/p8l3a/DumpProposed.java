/** v4 audit: builds the proposed extra test inputs with LogBuilder and prints id, length, hex. Encode only. */
public final class DumpProposed {
    public static void main(String[] a) {
        byte[][] in = {
            LogBuilder.wal().begin(1).begin(2).commit(1).begin(3).checkpoint(1, 3).build(),
            LogBuilder.wal().record(8, Hex.bytes("00 00 00 00 01")).begin(1).commit(1).build(),
            LogBuilder.wal().begin(9).begin(4294967295L).begin(2).begin(5)
                    .checkpoint(2, 5, 9, 4294967295L).begin(7).build(),
            LogBuilder.wal().begin(1)
                    .checkpoint(java.util.stream.LongStream.concat(java.util.stream.LongStream.range(1, 255),
                            java.util.stream.LongStream.of(0)).toArray())
                    .commit(1).build(),
        };
        for (int i = 0; i < in.length; i++) {
            String h = Hex.format(in[i]);
            System.out.println("X" + (i + 1) + " " + in[i].length + " " + (h.length() > 200 ? h.substring(0, 120) + " ... " + h.substring(h.length() - 90) : h));
        }
    }
}
