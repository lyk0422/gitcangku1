package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 批量离线合并冲突簇：对应 merge_cluster 表的一行。
 *
 * <p>批量合并中，某个观测项按三方规则重算出字段冲突时不拒绝整批，而是登记一条 PENDING 簇，
 * 观测内容保持冲突前当前版本、状态保持 RECEIVED；人工经冲突裁决接口处理后该簇置 RESOLVED。
 * 已 RESOLVED 的簇在许可撤销等后续事件中不重算。
 *
 * @param clusterId        簇唯一标识（批量请求标识与观测标识确定性拼接）
 * @param batchRequestId   产生该簇的批量请求标识
 * @param observationId    冲突所属观测标识
 * @param baseVersion      入簇时批量项的三方合并基线版本号
 * @param candidateLocation 入簇候选地点完整快照
 * @param candidateReading 入簇候选读数完整快照（十进制原文）
 * @param candidateNote    入簇候选备注完整快照
 * @param conflictFields   入簇时重算出的冲突字段名列表（固定顺序 location/reading/note）
 * @param status           簇状态：PENDING / RESOLVED
 * @param resolutionId     裁决写入的 conflict_resolution 标识；PENDING 时为 null
 * @param resolvedAtUtc    裁决完成时刻（UTC）；PENDING 时为 null
 */
public record MergeCluster(
        String clusterId,
        String batchRequestId,
        String observationId,
        int baseVersion,
        String candidateLocation,
        String candidateReading,
        String candidateNote,
        List<String> conflictFields,
        String status,
        String resolutionId,
        Instant resolvedAtUtc) {

    public static final String PENDING = "PENDING";
    public static final String RESOLVED = "RESOLVED";

    public boolean pending() {
        return PENDING.equals(status);
    }
}
