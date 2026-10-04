package com.example.starter.domain;

/** 运动间隔许可状态：只能创建（ACTIVE）或撤销（REVOKED）。 */
public enum PermitStatus {
    /** 有效许可，占用对应时段的运动间隔。 */
    ACTIVE,
    /** 已撤销许可，释放未来占用，历史轨迹保留不可变。 */
    REVOKED
}