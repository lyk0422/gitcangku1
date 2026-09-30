package com.example.starter.domain;

/**
 * 制品版本的依赖风险等级，按严重程度升序排列。
 *
 * <p>HIGH 及更高风险的制品版本在新建锁文件时默认禁止，必须持有有效的高风险豁免；
 * NONE/LOW/MEDIUM 不触发门禁。
 */
public enum RiskLevel {

    /** 未发现风险（或显式标记为无风险）。 */
    NONE,
    /** 低风险。 */
    LOW,
    /** 中风险。 */
    MEDIUM,
    /** 高风险：触发锁定门禁。 */
    HIGH,
    /** 严重风险：触发锁定门禁。 */
    CRITICAL;

    /**
     * 解析外部传入的风险等级代码，大小写不敏感；无法识别时抛出 IllegalArgumentException。
     */
    public static RiskLevel fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("riskLevel 不能为空");
        }
        return RiskLevel.valueOf(code.trim().toUpperCase());
    }

    /** 是否达到锁定门禁阈值（HIGH 或更高）。 */
    public boolean blocksLock() {
        return ordinal() >= HIGH.ordinal();
    }
}
