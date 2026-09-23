package com.example.starter.blind;

/**
 * 操作者角色，本题信任本地测试头 X-Role。
 */
public enum Role {
    /** 协调员（实验负责人）：实验管理、登记、退组、揭盲申请、提交职责轮换单。 */
    COORDINATOR,
    /** 审阅员：仅可普通查询、批准揭盲申请。 */
    REVIEWER,
    /** 数据采集者：仅可见/提交本人采集范围内未结束受试者的最小字段。 */
    DATA_COLLECTOR,
    /** 随机化保管者：持有随机化映射，不得同时承担数据采集。 */
    RANDOMIZATION_CUSTODIAN,
    /** 安全审阅者：按最小知情获得安全审阅所需字段。 */
    SAFETY_REVIEWER;

    /** 是否为可被轮换单调整的三类职责角色。 */
    public boolean isDutyRole() {
        return this == DATA_COLLECTOR
                || this == RANDOMIZATION_CUSTODIAN
                || this == SAFETY_REVIEWER;
    }
}
