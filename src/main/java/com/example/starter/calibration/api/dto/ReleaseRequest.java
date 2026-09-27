package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量放行请求。每批 1～50 条测量键，整批原子生效。
 *
 * @param keys      测量键列表
 * @param requestId 幂等请求 ID（可选；提供时同参重放返回首次快照，异参 409，失败不占键）
 */
public record ReleaseRequest(List<String> keys, String requestId) {
}
