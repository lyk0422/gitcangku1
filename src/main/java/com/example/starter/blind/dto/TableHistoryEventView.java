package com.example.starter.blind.dto;

/**
 * 区组随机表历史事件；按事件发生时刻升序（左闭右开的提交顺序裁决），只读且永不改写。
 * 事件只记录版本、容量、处理代码集合与状态变化，不记录处理序列。
 *
 * @param eventType      VERSION_CREATED / VERSION_SEALED / VERSION_CLOSED
 * @param experimentId   实验编号
 * @param blockNo        区组号
 * @param versionNo      关联版本号
 * @param parentVersionNo 前驱版本号；无前驱时为 null
 * @param blockCapacity  事件时的区组容量，单位：个；与容量无关的事件也回填该版本容量
 * @param treatmentCodes 处理代码集合快照（字典序升序逗号分隔）
 * @param sealKey        封存密钥；仅 VERSION_SEALED 事件有值，其余为 null
 * @param sequenceDigest 随机表摘要；仅 VERSION_SEALED 事件有值，其余为 null
 * @param eventTime      事件时刻，Unix 毫秒，UTC
 */
public record TableHistoryEventView(
        String eventType,
        String experimentId,
        int blockNo,
        int versionNo,
        Integer parentVersionNo,
        int blockCapacity,
        String treatmentCodes,
        String sealKey,
        String sequenceDigest,
        long eventTime
) {
}
