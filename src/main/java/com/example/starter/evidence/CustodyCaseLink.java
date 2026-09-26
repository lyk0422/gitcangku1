package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 双案保管链事件，对应 custody_case_link 表。只追加、不可变；
 * 来源案件移出链与目标案件移入链共用同一 transferId。
 *
 * @param id           主键
 * @param caseKey      该链事件所属案件键
 * @param evidenceKey  证物业务键
 * @param transferId   关联跨案移交批次业务键
 * @param direction    链方向
 * @param orderVersion 事件使用的移交令版本
 * @param actorId      事件操作人
 * @param createdAt    事件时间（UTC）
 */
public record CustodyCaseLink(
        Long id,
        String caseKey,
        String evidenceKey,
        String transferId,
        ChainDirection direction,
        String orderVersion,
        String actorId,
        LocalDateTime createdAt) {
}
