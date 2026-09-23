package com.example.starter.domain;

/**
 * 坐标模式：精确坐标，或以 {@code *} 结尾的前缀通配模式（如 {@code com.legacy.*}）。
 *
 * <p>{@code *} 只允许出现在模式末尾且模式长度大于 1，除此之外均为非法语法。
 * 前缀模式匹配所有以字面量前缀开头的坐标；精确模式只匹配自身。
 */
public record CoordinatePattern(String raw, String prefix, boolean wildcard) {

    public CoordinatePattern {
        raw = raw == null ? "" : raw.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("坐标模式不能为空");
        }
        if (raw.length() > 128) {
            throw new IllegalArgumentException("坐标模式长度不能超过 128");
        }
        int star = raw.indexOf('*');
        if (star >= 0 && (star != raw.length() - 1 || raw.length() == 1)) {
            throw new IllegalArgumentException("坐标模式仅允许以 * 结尾的前缀通配: " + raw);
        }
        wildcard = star >= 0;
        prefix = wildcard ? raw.substring(0, raw.length() - 1) : raw;
    }

    /** 按字面量解析模式，语法非法时抛出 IllegalArgumentException。 */
    public static CoordinatePattern of(String raw) {
        return new CoordinatePattern(raw, null, false);
    }

    /** 判断给定坐标是否命中本模式。 */
    public boolean matches(String coordinate) {
        if (coordinate == null) {
            return false;
        }
        return wildcard ? coordinate.startsWith(prefix) : coordinate.equals(prefix);
    }

    /**
     * 两个同平台模式是否可能重叠命中同一坐标：
     * 精确-精确看相等；精确-前缀看包含；前缀-前缀看前缀是否互为前缀。
     */
    public static boolean overlaps(CoordinatePattern a, CoordinatePattern b) {
        if (!a.wildcard && !b.wildcard) {
            return a.prefix.equals(b.prefix);
        }
        if (!a.wildcard) {
            return a.prefix.startsWith(b.prefix);
        }
        if (!b.wildcard) {
            return b.prefix.startsWith(a.prefix);
        }
        return a.prefix.startsWith(b.prefix) || b.prefix.startsWith(a.prefix);
    }
}
