package com.example.starter.observation;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * 观测读数量纲：每条观测记录在创建时固定，后续提交不得跨量纲。
 *
 * <p>长度（LENGTH）支持 m、cm，规范值为 m（cm 除以 100，精确左移两位小数）；
 * 温度（TEMPERATURE）支持 C、K，规范值为 C（K 减 273.15）。
 * 规范化使用精确十进制运算，不做舍入；合并比较一律基于精确规范值，显示层可自行舍入。
 */
public enum Dimension {
    LENGTH(Set.of("m", "cm")),
    TEMPERATURE(Set.of("C", "K"));

    /**
     * 温度量纲开尔文转摄氏的精确偏移量。
     */
    private static final BigDecimal KELVIN_OFFSET = new BigDecimal("273.15");

    private final Set<String> units;

    Dimension(Set<String> units) {
        this.units = units;
    }

    /**
     * 判断单位是否属于本量纲；单位按原文精确匹配（大小写敏感），前后空白忽略。
     */
    public boolean supports(String unit) {
        return unit != null && units.contains(unit.trim());
    }

    /**
     * 大小写不敏感地解析量纲名；空串视为未提供（返回 null 表示无量纲），非法值也返回 null，
     * 由业务层区分“未提供”和“非法”并统一报 400。
     */
    public static Dimension parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Dimension.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 将原始读数规范化为量纲基准单位的精确十进制字符串；调用前必须先经 supports 校验。
     * 结果为精确值（toPlainString），比较使用 BigDecimal.compareTo，与 scale 无关。
     */
    public String normalize(String unit, String value) {
        BigDecimal decimal = new BigDecimal(value.trim());
        BigDecimal normalized = switch (this) {
            case LENGTH -> "cm".equals(unit.trim()) ? decimal.movePointLeft(2) : decimal;
            case TEMPERATURE -> "K".equals(unit.trim()) ? decimal.subtract(KELVIN_OFFSET) : decimal;
        };
        return normalized.toPlainString();
    }
}
