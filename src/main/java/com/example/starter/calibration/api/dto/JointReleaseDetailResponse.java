package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 联合放行记录查询响应：批次头信息加全部测量明细，明细只读稳定排序。
 *
 * @param jointBatchKey 联合批次业务键
 * @param requestId     请求幂等键
 * @param releasedBy    放行人
 * @param releasedAt    放行时间（UTC）
 * @param items         按测量键字典序稳定排序的放行明细
 */
public record JointReleaseDetailResponse(
        String jointBatchKey,
        String requestId,
        String releasedBy,
        Instant releasedAt,
        List<JointReleaseItemResponse> items) {
}
