package com.example.starter.blind;

/**
 * 操作者角色，本题信任本地测试头 X-Role。
 */
public enum Role {
    /** 协调员：实验管理、登记、退组、揭盲申请、为参与者领取处理包。 */
    COORDINATOR,
    /** 审阅员：仅可普通查询、批准揭盲申请。 */
    REVIEWER,
    /** 可信供应员：登记处理包（含 A/B 代码）、查询包的发放状态；不可读参与者分配映射。 */
    SUPPLY
}