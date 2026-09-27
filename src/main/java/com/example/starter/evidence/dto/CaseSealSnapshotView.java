package com.example.starter.evidence.dto;

import com.example.starter.evidence.SnapshotSide;

import java.time.LocalDateTime;

/**
 * 双案封存快照视图。
 *
 * @param transferId       所属跨案移交业务 ID
 * @param caseKey          快照所属案件
 * @param side             快照侧别：FROM / TO
 * @param evidenceKey      证物业务键
 * @param originalLocation 移交时原存放位置固化；null 表示未登记库位
 * @param sealVersion      封签版本固化
 * @param orderVersion     移交令版本固化
 * @param chainEventId     对应案件保管链事件 ID
 * @param createdAt        快照固化时刻（UTC）
 */
public record CaseSealSnapshotView(
        String transferId,
        String caseKey,
        SnapshotSide side,
        String evidenceKey,
        String originalLocation,
        int sealVersion,
        int orderVersion,
        long chainEventId,
        LocalDateTime createdAt) {
}
