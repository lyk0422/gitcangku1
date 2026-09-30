package com.example.starter.domain;

/**
 * 制品版本的依赖风险级别，按严重程度从低到高排列。
 *
 * <p>HIGH 及以上风险的依赖版本默认禁止写入新锁文件，除非持有有效的依赖风险豁免。
 */
public enum RiskLevel {
    /** 未标记风险（默认）。 */
    NONE,
    /** 低风险。 */
    LOW,
    /** 中风险。 */
    MEDIUM,
    /** 高风险：锁定门禁阈值，命中须提供有效豁免。 */
    HIGH,
    /** 严重风险：同 HIGH，锁定须提供有效豁免。 */
    CRITICAL;

    /** 是否达到 HIGH 门禁阈值（HIGH、CRITICAL）。 */
    public boolean atLeastHigh() {
        return ordinal() >= HIGH.ordinal();
    }

    /** 按名称解析，空白或未知名称返回 null（由调用方决定错误语义）。 */
    public static RiskLevel parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        for (RiskLevel level : values()) {
            if (level.name().equalsIgnoreCase(value)) {
                return level;
            }
        }
        return null;
    }
}
