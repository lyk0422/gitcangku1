package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 双案封存快照实体，对应 case_seal_snapshot 表。同一 transferId 每件证物两行
 * （FROM 来源案 / TO 目标案），固化双方案件、原位置、封签版本与令版本；只追加，撤销不删除。
 *
 * @param id             主键
 * @param transferId     所属跨案移交业务 ID
 * @param caseKey        快照所属案件
 * @param side           快照侧别
 * @param evidenceKey    关联证物业务键
 * @param originalLocation 移交时原存放位置固化；null 表示未登记库位
 * @param sealVersion    移交时封签版本固化
 * @param orderVersion   移交令版本固化
 * @param chainEventId   对应案件保管链事件 ID
 * @param createdAt      快照固化时刻（UTC）
 */
public record CaseSealSnapshot(
        Long id,
        String transferId,
        String caseKey,
        SnapshotSide side,
        String evidenceKey,
        String originalLocation,
        int sealVersion,
        int orderVersion,
        Long chainEventId,
        LocalDateTime createdAt) {
}
