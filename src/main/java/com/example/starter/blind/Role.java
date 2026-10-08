package com.example.starter.blind;

/**
 * 操作者角色，本题信任本地测试头 X-Role。
 */
public enum Role {
    /** 协调员：实验管理、登记、退组、揭盲申请。 */
    COORDINATOR,
    /** 审阅员：仅可普通查询、批准揭盲申请、闭组后查看结果汇总。 */
    REVIEWER,
    /** 分析员：凭一级码接收样本获得二级码，仅本人可用二级码录入一个整数结果。 */
    ANALYST
}
