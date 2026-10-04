package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 受监管交接实体，对应 regulated_transfer 表。
 * 检查点名单、冻结封条与各方角色创建后不可修改；version 为乐观锁版本，每次状态变更递增。
 *
 * @param id              主键
 * @param transferKey     受监管交接业务键，全局唯一
 * @param evidenceKey     关联证物业务键
 * @param fromCustodian   发起时的保管人（原保管人），仅其可取消
 * @param toCustodian     指定收件人，仅其可接收
 * @param transporterId   指定运送人，仅其可签到
 * @param sealNo          发起时冻结的封条编号
 * @param checkpointCount 检查点总数，2-8，创建后不变
 * @param version         乐观锁版本，自 0 起每次状态变更递增
 * @param status          交接状态
 * @param createdAt       发起时间（UTC）
 * @param decidedAt       接收或取消时间（UTC）；null 表示仍在途或冻结未决
 */
public record RegulatedTransfer(
        Long id,
        String transferKey,
        String evidenceKey,
        String fromCustodian,
        String toCustodian,
        String transporterId,
        String sealNo,
        int checkpointCount,
        long version,
        RegulatedTransferStatus status,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}