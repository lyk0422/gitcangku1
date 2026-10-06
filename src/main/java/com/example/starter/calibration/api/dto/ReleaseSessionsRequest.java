package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 会话批量放行请求。只能引用已关闭且非空的会话；任一会话不满足条件整批拒绝（422）。
 *
 * @param requestId  幂等请求 ID
 * @param sessionIds 会话 ID 列表（1～50 个，不重复）
 */
public record ReleaseSessionsRequest(
        String requestId,
        List<Long> sessionIds) {
}
