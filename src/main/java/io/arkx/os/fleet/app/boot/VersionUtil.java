package io.arkx.os.fleet.app.boot;

/**
 * 语义化版本号工具（boot 零依赖极简版，纯 JDK）。
 *
 * <p>
 * 与 app-switch 的 {@code VersionComparator} 语义一致，但 boot 为「零依赖引导器」
 * 无法引用该模块，故在此保留一份极简实现，仅覆盖版本号比较这一处场景。
 * </p>
 */
public final class VersionUtil {

    private VersionUtil() {
    }

    /** 语义化版本比较：按 {@code .} 拆段逐段数值比较，避免 {@code 10.0 < 2.0} 的字典序错误。 */
    public static int compareVersion(String a, String b) {
        if (a == null)
            a = "";
        if (b == null)
            b = "";
        if (a.startsWith("v") || a.startsWith("V"))
            a = a.substring(1);
        if (b.startsWith("v") || b.startsWith("V"))
            b = b.substring(1);

        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "";
            String sb = i < pb.length ? pb[i] : "";
            int c = compareSegment(sa, sb);
            if (c != 0)
                return c;
        }
        return 0;
    }

    private static int compareSegment(String a, String b) {
        Integer na = leadingInt(a);
        Integer nb = leadingInt(b);
        if (na != null && nb != null) {
            int c = Integer.compare(na, nb);
            if (c != 0)
                return c;
            return compareSegment(a.substring(digits(na)), b.substring(digits(nb)));
        }
        if (na != null)
            return 1;
        if (nb != null)
            return -1;
        return a.compareTo(b);
    }

    /** 从目录名（如 {@code arkxos-fleet-runner-cli-1.0}）提取版本号（最后一个 '-' 之后）。 */
    public static String versionOf(String dirName) {
        int dash = dirName.lastIndexOf('-');
        return dash >= 0 && dash < dirName.length() - 1 ? dirName.substring(dash + 1) : dirName;
    }

    private static int digits(int v) {
        if (v == 0)
            return 1;
        int d = 0;
        int t = Math.abs(v);
        while (t > 0) {
            d++;
            t /= 10;
        }
        return d;
    }

    private static Integer leadingInt(String s) {
        if (s == null || s.isEmpty())
            return null;
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i)))
            i++;
        if (i == 0)
            return null;
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
