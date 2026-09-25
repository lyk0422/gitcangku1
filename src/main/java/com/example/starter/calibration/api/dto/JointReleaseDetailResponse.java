package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 联合放行记录及按批次的测量放行明细查询响应。items 按测量键字典序稳定排序。
 *
 * @param jointBatchKey 联合批次键
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 * @param items         本批全部测量放行明细（不可变快照）
 */
public record JointReleaseDetailResponse(String jointBatchKey, String releasedBy, Instant releasedAt,
                                         List<JointReleaseItemResponse> items) {
}
