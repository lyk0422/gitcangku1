package com.example.starter.evidence.dto;

import com.example.starter.evidence.RegulatedTransferStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 受监管交接完整视图：交接主体 + 冻结的检查点名单 + 签到记录 + 异常记录。
 * 名单与封条在发起时冻结，后续新交接不改旧链。
 *
 * @param transferKey     受监管交接业务键
 * @param evidenceKey     关联证物业务键
 * @param fromCustodian   发起时的保管人
 * @param toCustodian     指定收件人
 * @param transporterId   指定运送人
 * @param sealNo          发起时冻结的封条编号
 * @param checkpointCount 检查点总数
 * @param version         当前乐观锁版本
 * @param status          交接状态
 * @param createdAt       发起时间（UTC）
 * @param decidedAt       接收或取消时间（UTC）；null 表示未决
 * @param checkpoints     冻结的检查点名单（按点号顺序）
 * @param checkins        签到记录（按点号顺序）
 * @param exceptions      异常记录（按发生顺序）
 */
public record RegulatedTransferView(
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
        LocalDateTime decidedAt,
        List<CheckpointView> checkpoints,
        List<CheckinView> checkins,
        List<ExceptionView> exceptions) {
}