package com.example.starter.blind;

/**
 * 操作者角色，本题信任本地测试头 X-Role。
 */
public enum Role {
    /** 协调员：实验管理、登记、退组、揭盲申请。 */
    COORDINATOR,
    /** 审阅员：仅可普通查询、批准揭盲申请。 */
    REVIEWER,
    /** 未盲管理人员：可执行中心激活/暂停/恢复/关闭等管理操作。 */
    UNBLINDED_MANAGER
}
