package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 会话批量放行请求。只能引用已关闭的完整会话，整批原子生效；
 * 任一成员不合格或证书撤销则整批 422。放行人通过 X-Actor-Id 请求头提供。
 *
 * @param requestId 幂等请求键，全局唯一
 * @param keys      会话键列表（1～50 个，不重复）
 */
public record SessionReleaseRequest(String requestId, List<String> keys) {
}
