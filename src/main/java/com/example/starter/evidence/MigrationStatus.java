package com.example.starter.evidence;

/**
 * 介质迁移状态。
 * PENDING 待完成（每件证物至多一条）；COMPLETED 已切换（新介质 ACTIVE、旧介质 RETIRED）；
 * CANCELLED 已取消（源副本不变，不可被旧验证重放复活）。
 */
public enum MigrationStatus {
    PENDING,
    COMPLETED,
    CANCELLED
}
