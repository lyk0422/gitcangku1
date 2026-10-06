package com.example.starter.incident;

/**
 * 资源租约状态机：ACTIVE → RELEASED。
 * 到期不是租约的存储状态：ACTIVE 租约在当前时刻 >= expires_at 时视为已到期，
 * 由访问或命令按注入时钟即时判定，不做后台扫描。RELEASED 为终态。
 */
public enum AssignmentStatus {

    /** 生效中（是否已到期按 expires_at 与当前时刻判定）。 */
    ACTIVE,

    /** 已回收（任务完成或取消时释放资源）。 */
    RELEASED;
}
