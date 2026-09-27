package com.example.starter.permit;

import java.time.Instant;
import java.util.List;

/**
 * 冲突簇人工裁决不可变记录：对应 permit_cluster_resolution 表一行。
 *
 * @param clusterId             冲突簇标识
 * @param requestId             裁决请求标识
 * @param observationKeys       簇内观测业务键清单
 * @param chosenObservationKey  裁决选定的观测业务键
 * @param chosenContent         裁决选定的观测内容原文
 * @param chosenPermitId        裁决选定观测所属许可标识
 * @param operator              操作者标识
 * @param resolvedAt            裁决完成时刻（UTC）
 */
public record ClusterResolution(
        String clusterId,
        String requestId,
        List<String> observationKeys,
        String chosenObservationKey,
        String chosenContent,
        String chosenPermitId,
        String operator,
        Instant resolvedAt) {
}
