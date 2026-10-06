package com.example.starter.observation.geo;

import java.math.BigDecimal;

/**
 * 二维坐标观测的十进制工具：统一校验模式与按值规范化（用于幂等指纹与整组比较）。
 * 十进制按数值比较，文本字段不参与本工具。
 */
final class GeoDecimal {

    /**
     * 十进制字符串校验模式：可选负号、整数部分必填、小数部分最多三位。
     */
    static final String DECIMAL_PATTERN = "-?\\d+(\\.\\d{1,3})?";

    /**
     * 校验失败提示信息。
     */
    static final String DECIMAL_MESSAGE = "must be a decimal string with at most 3 fraction digits";

    private GeoDecimal() {
    }

    /**
     * 按数值解析十进制字符串；调用前须已通过 {@link #DECIMAL_PATTERN} 校验。
     */
    static BigDecimal parse(String value) {
        return new BigDecimal(value);
    }

    /**
     * 按数值规范化十进制字符串（1.0 与 1.00 归一为同一表示），用于幂等指纹的结构化比较。
     */
    static String normalize(String value) {
        BigDecimal decimal = new BigDecimal(value);
        if (decimal.signum() == 0) {
            return "0";
        }
        return decimal.stripTrailingZeros().toPlainString();
    }

    /**
     * 判断两个十进制字符串数值是否相等；任一侧为 null 时按引用相等处理（墓碑版本）。
     */
    static boolean valueEquals(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
    }
}
