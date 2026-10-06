package com.example.starter.incident;

/**
 * 执行时段安排状态机：SCHEDULED → STARTED 或 SCHEDULED → REVOKED。
 * STARTED 为不可逆状态：已开始安排不可改时、不可撤销，任务完成或取消后仍保留
 * 原安排作业量（继续计入人员休息与日上限）；REVOKED 不再计入任何约束。
 */
public enum TaskScheduleStatus {

    /** 已安排未开始：可替换时段、可撤销。 */
    SCHEDULED,

    /** 已开始：不可改时、不可撤销，作业量保留。 */
    STARTED,

    /** 已撤销：仅未开始安排可撤销，撤销后释放休息与日上限约束。 */
    REVOKED;
}
