package com.example.starter.incident;

/**
 * 在场凭证状态机：ACTIVE 当前有效 → SUPERSEDED（本人重新签到）/ REVOKED（当前指挥撤销）
 * / FROZEN（任务完成时与另一份同时冻结）。除 ACTIVE 外均为不可用状态。
 * 凭证是否在有效期内还需结合 issued_at/expires_at 按左闭右开区间判断。
 */
public enum AttendanceStatus {

    /** 当前代次有效；是否在 60 秒有效期内另按签发/到期时刻判断。 */
    ACTIVE,

    /** 被本人重新签到推进取代的历史代次，永久失效。 */
    SUPERSEDED,

    /** 被当前指挥人撤销，永久失效。 */
    REVOKED,

    /** 任务 DONE 时冻结；签发与到期时刻固定保留，作为首次完成证据。 */
    FROZEN;
}
