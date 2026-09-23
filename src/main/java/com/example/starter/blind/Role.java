package com.example.starter.blind;

/**
 * 操作者角色，本题信任本地测试头 X-Role。
 */
public enum Role {
    /** 协调员：实验管理、登记、退组、揭盲申请；已批准揭盲后可登记泄露披露。 */
    COORDINATOR,
    /** 审阅员：仅可普通查询、批准揭盲申请。 */
    REVIEWER,
    /** 合规负责人：发起/确认隔离单，不参与揭盲申请与批准。 */
    COMPLIANCE
}
