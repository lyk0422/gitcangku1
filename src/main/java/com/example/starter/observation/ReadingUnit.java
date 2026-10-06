package com.example.starter.observation;

import java.math.BigDecimal;

/**
 * 带量纲读数单位：长度支持 m/cm，温度支持 C/K。
 * 规范值长度为 m（cm 除 100），温度为 C（K 减 273.15）；换算使用精确十进制，不舍入。
 */
public enum ReadingUnit {
    METER("m", Dimension.LENGTH),
    CENTIMETER("cm", Dimension.LENGTH),
    CELSIUS("C", Dimension.TEMPERATURE),
    KELVIN("K", Dimension.TEMPERATURE);

    private static final BigDecimal KELVIN_OFFSET = new BigDecimal("273.15");

    private final String symbol;
    private final Dimension dimension;

    ReadingUnit(String symbol, Dimension dimension) {
        this.symbol = symbol;
        this.dimension = dimension;
    }

    public String symbol() {
        return symbol;
    }

    public Dimension dimension() {
        return dimension;
    }

    /**
     * 按符号精确匹配单位（单位符号大小写敏感）；不支持的单位返回 null，由业务层统一报 400。
     */
    public static ReadingUnit fromSymbol(String symbol) {
        if (symbol == null) {
            return null;
        }
        for (ReadingUnit unit : values()) {
            if (unit.symbol.equals(symbol)) {
                return unit;
            }
        }
        return null;
    }

    /**
     * 换算为规范值：cm 除 100，K 减 273.15，其余原样；精确十进制运算，结果可能超过三位小数。
     */
    public BigDecimal normalize(BigDecimal value) {
        return switch (this) {
            case METER, CELSIUS -> value;
            case CENTIMETER -> value.movePointLeft(2);
            case KELVIN -> value.subtract(KELVIN_OFFSET);
        };
    }
}
