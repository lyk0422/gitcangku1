package com.example.starter.observation;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 读数限定符工具：EXACT（单值）、LT（小于阈值）、GT（大于阈值）。
 * 限定符与读数值是不可拆分的一组：数值相等且限定符相同才是等价，
 * 数值按 BigDecimal 比较（5.0 与 5.000 等价），EXACT 5 与 LT 5 不同。
 */
public final class ReadingQualifiers {

    public static final String EXACT = "EXACT";
    public static final String LT = "LT";
    public static final String GT = "GT";

    private static final Set<String> ALL = Set.of(EXACT, LT, GT);

    private ReadingQualifiers() {
    }

    /**
     * 规范化请求中的限定符：缺省（null/空白）按 EXACT；其余转为大写。
     * 非法值返回 null，由调用方统一报 400。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return EXACT;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(normalized) ? normalized : null;
    }

    /**
     * 旧数据默认值：存储为 NULL 的限定符（旧普通读数）读取时按 EXACT 处理，不回写。
     */
    public static String orDefault(String stored) {
        return stored == null ? EXACT : stored;
    }

    /**
     * 整组等价判断：限定符相同且数值相等（按 BigDecimal 数值比较，忽略小数位书写差异）。
     * 任一侧读数为 null（墓碑）时按引用/null 相等处理。
     */
    public static boolean groupEquals(String leftQualifier, String leftValue,
                                      String rightQualifier, String rightValue) {
        if (leftValue == null || rightValue == null) {
            return Objects.equals(leftValue, rightValue)
                    && Objects.equals(leftQualifier, rightQualifier);
        }
        return Objects.equals(leftQualifier, rightQualifier)
                && new BigDecimal(leftValue).compareTo(new BigDecimal(rightValue)) == 0;
    }

    /**
     * 数值相等判断（忽略限定符），用于识别“一侧只改数值”的拆分变更场景。
     */
    public static boolean valueEquals(String leftValue, String rightValue) {
        if (leftValue == null || rightValue == null) {
            return Objects.equals(leftValue, rightValue);
        }
        return new BigDecimal(leftValue).compareTo(new BigDecimal(rightValue)) == 0;
    }
}