package com.example.starter.blind;

/**
 * 操作者角色，本题信任本地测试头 X-Role。
 */
public enum Role {
    /** 协调员：实验管理、登记、退组、揭盲申请；同时是中心启用后的分配方。 */
    COORDINATOR,
    /** 审阅员：仅可普通查询、批准揭盲申请。 */
    REVIEWER,
    /** 中心负责人：提交中心配置版本与启用申请，可查启用版本与名额。 */
    LEAD,
    /** 独立确认角色：与中心负责人相互独立，核对区组及名额后确认启用。 */
    CONFIRMER
}
