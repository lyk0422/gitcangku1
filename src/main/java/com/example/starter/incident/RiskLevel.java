package com.example.starter.incident;

/**
 * 疏散区域风险等级。
 */
public enum RiskLevel {

    /** 高风险。 */
    HIGH,

    /** 中风险。 */
    MEDIUM,

    /** 低风险。 */
    LOW;

    /**
     * 解析风险等级字符串，非法值返回 null（由调用方给出 400）。
     */
    public static RiskLevel parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.strip()) {
            case "HIGH" -> HIGH;
            case "MEDIUM" -> MEDIUM;
            case "LOW" -> LOW;
            default -> null;
        };
    }
}
