package com.example.starter.observation;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * 一次带量纲读数输入：原值、原始单位与精确规范值。
 *
 * <p>解析规则：dimension 与 readingUnit 必须同时出现或同时缺省（同时缺省表示旧无单位读数，返回 null）；
 * 单位必须受支持且属于所给量纲，值必须是最多三位小数的十进制字符串；任何违反均抛 400。
 * 规范值按单位精确换算（cm/100、K-273.15），不舍入，合并比较使用该精确值。
 *
 * @param dimension       读数量纲
 * @param unit            原始单位
 * @param value           原始读数（十进制原文，最多三位小数）
 * @param normalizedValue 精确规范读数（长度 m、温度 C，十进制原文，可能超过三位小数）
 */
public record DimensionalReading(Dimension dimension, ReadingUnit unit, String value, String normalizedValue) {

    private static final Pattern VALUE_PATTERN = Pattern.compile("-?\\d+(\\.\\d{1,3})?");

    /**
     * 解析请求中的量纲读数；dimension 与 readingUnit 均缺省时返回 null（旧无单位读数，不推断量纲）。
     */
    public static DimensionalReading parse(String dimension, String unit, String value) {
        if (dimension == null && unit == null) {
            return null;
        }
        if (dimension == null || unit == null) {
            throw ApiException.badRequest("dimension and readingUnit must be provided together");
        }
        Dimension parsedDimension = Dimension.fromValue(dimension);
        if (parsedDimension == null) {
            throw ApiException.badRequest("unsupported dimension: " + dimension);
        }
        ReadingUnit parsedUnit = ReadingUnit.fromSymbol(unit);
        if (parsedUnit == null || parsedUnit.dimension() != parsedDimension) {
            throw ApiException.badRequest(
                    "unsupported unit for dimension " + parsedDimension.name() + ": " + unit);
        }
        if (value == null || !VALUE_PATTERN.matcher(value).matches()) {
            throw ApiException.badRequest(
                    "reading must be a decimal string with at most 3 fraction digits");
        }
        return new DimensionalReading(parsedDimension, parsedUnit, value,
                parsedUnit.normalize(new BigDecimal(value)).stripTrailingZeros().toPlainString());
    }
}
