package com.example.starter.evidence;

/**
 * 检验预约状态（只进不退的状态机）。
 * SCHEDULED 已预约未开始（占用检验台容量与证物时段，可取消）；
 * IN_PROGRESS 检验中（证物为 EXAMINATION，不可取消）；
 * COMPLETED 已完成（不可再变）；CANCELLED 未开始即取消（不可再变，释放台位）。
 */
public enum ReservationStatus {
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
