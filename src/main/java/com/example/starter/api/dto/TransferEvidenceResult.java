package com.example.starter.api.dto;

import java.util.List;

/**
 * 转配证据查询结果（只读，按航线、桶稳定排序）。
 *
 * @param transferKey 转配单标识
 * @param requestId   激活请求标识
 * @param activatedAt 激活时间，epoch 毫秒（UTC）
 * @param routes      各参与航线转配前后序列、版本与审查依据
 * @param buckets     各受影响桶转配前后全量占用与容量
 */
public record TransferEvidenceResult(
        String transferKey,
        String requestId,
        Long activatedAt,
        List<RouteEvidenceDto> routes,
        List<BucketCapacityDto> buckets) {
}
