package com.example.starter.incident;

import java.time.Instant;

/**
 * 互助租约分次归还流水实体，对应 incident_resource_returns 表。
 * 每条记录一次实际归还：quantity 为本件数（正整数），returnedBy 为归还人
 * （归还时目标事件的当前指挥人），returnedAt/createdAt 均为 UTC。
 * 同一租约各次归还数量之和不超过征调总量，超出由条件更新与事务回滚拒绝。
 */
public record ResourceReturn(
        long id,
        long leaseId,
        long quantity,
        String returnedBy,
        Instant returnedAt,
        Instant createdAt) {
}
