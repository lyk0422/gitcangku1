package com.example.starter.permit;

import java.util.List;

/**
 * 批量离线合并响应：整批预校验通过后返回全部已合并观测与待裁决冲突簇标识。
 *
 * @param acceptedCount        本次批次写入的观测条数
 * @param observations         已合并观测（按批次顺序）
 * @param pendingClusterIds    尚待人工裁决的冲突簇标识
 * @param quotaAfterMergeByPermit 合并后各许可的已用计数（permitId -> acceptedCount）
 */
public record BatchMergeResponse(
        int acceptedCount,
        List<PermitObservationResponse> observations,
        List<String> pendingClusterIds,
        java.util.Map<String, Integer> quotaAfterMergeByPermit) {
}
