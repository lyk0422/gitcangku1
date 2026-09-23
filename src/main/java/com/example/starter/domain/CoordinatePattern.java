package com.example.starter.domain;

import java.util.regex.Pattern;

/**
 * 原坐标匹配模式：以 {@code name:version} 形式匹配精确坐标，支持 {@code *}（任意序列）
 * 与 {@code ?}（任意单个字符）通配；不含冒号的模式等价于追加 {@code :*}（任意版本）。
 *
 * <p>同时提供两个模式是否可能命中同一坐标的静态判定（交集非空），用于策略发布时
 * 校验“同一策略内规则不得重叠命中”。
 */
public final class CoordinatePattern {

    private static final Pattern LEGAL = Pattern.compile("[A-Za-z0-9_.\\-:*?]+");

    private final String raw;
    private final String canonical;
    private final Pattern regex;

    private CoordinatePattern(String raw, String canonical) {
        this.raw = raw;
        this.canonical = canonical;
        this.regex = Pattern.compile(globToRegex(canonical));
    }

    /** 解析并校验原坐标模式。 */
    public static CoordinatePattern parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("原坐标模式不能为空");
        }
        String trimmed = raw.trim();
        if (!LEGAL.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("原坐标模式含非法字符: " + trimmed);
        }
        String canonical = trimmed.contains(":") ? trimmed : trimmed + ":*";
        return new CoordinatePattern(trimmed, canonical);
    }

    /** 模式是否命中给定精确坐标。 */
    public boolean matches(Coordinate coordinate) {
        return regex.matcher(coordinate.name() + ":" + coordinate.version()).matches();
    }

    /** 规范化模式文本（无冒号时已补 :* 之外保持原样）。 */
    public String canonical() {
        return canonical;
    }

    public String raw() {
        return raw;
    }

    /**
     * 两个 glob 模式是否存在共同可命中的坐标字符串。
     * 采用动态规划模拟两模式逐位对齐：字面量须一致，{@code ?} 消耗一个字符，{@code *} 可零或多字符。
     */
    public static boolean overlap(String firstRaw, String secondRaw) {
        String a = parse(firstRaw).canonical;
        String b = parse(secondRaw).canonical;
        boolean[][] reachable = new boolean[a.length() + 1][b.length() + 1];
        reachable[0][0] = true;
        for (int i = 0; i <= a.length(); i++) {
            for (int j = 0; j <= b.length(); j++) {
                if (!reachable[i][j]) {
                    continue;
                }
                if (i < a.length() && a.charAt(i) == '*') {
                    // 左侧 * 匹配零个字符
                    reachable[i + 1][j] = true;
                    // 左侧 * 消耗右侧一个可生成字符（字面量或 ?），或展开右侧 *
                    if (j < b.length()) {
                        reachable[i][j + 1] = true;
                    }
                }
                if (j < b.length() && b.charAt(j) == '*') {
                    reachable[i][j + 1] = true;
                    if (i < a.length()) {
                        reachable[i + 1][j] = true;
                    }
                }
                if (i < a.length() && j < b.length() && a.charAt(i) != '*' && b.charAt(j) != '*') {
                    char ca = a.charAt(i);
                    char cb = b.charAt(j);
                    if (ca == '?' || cb == '?' || ca == cb) {
                        reachable[i + 1][j + 1] = true;
                    }
                }
            }
        }
        return reachable[a.length()][b.length()];
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
        }
        return sb.append('$').toString();
    }
}
