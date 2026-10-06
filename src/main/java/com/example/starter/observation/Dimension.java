package com.example.starter.observation;

import java.util.Locale;

/**
 * 读数量纲：长度（LENGTH，规范单位 m）或温度（TEMPERATURE，规范单位 C）。
 * 每条观测在创建时固定量纲，后续提交不得跨量纲；旧无单位读数量纲为 null，不自动推断。
 */
public enum Dimension {
    LENGTH,
    TEMPERATURE;

    /**
     * 大小写不敏感地解析量纲名称；不支持的值返回 null，由业务层统一报 400。
     */
    public static Dimension fromValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "LENGTH" -> LENGTH;
            case "TEMPERATURE" -> TEMPERATURE;
            default -> null;
        };
    }

    /**
     * 该量纲的规范单位符号：长度为 m，温度为 C。
     */
    public String canonicalUnit() {
        return this == LENGTH ? "m" : "C";
    }
}
