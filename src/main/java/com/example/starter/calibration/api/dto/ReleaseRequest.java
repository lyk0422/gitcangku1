package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量放行请求。每批 1～50 条测量键，整批原子生效。
 * requestId 非空时启用幂等去重（操作者为 X-Actor-Id 请求头，必须非空）。
 *
 * @param keys            测量键列表
 * @param requestId       全局幂等请求 ID；为空则不启用幂等
 * @param expectedVersion 期望版本，参与幂等指纹结构化比较
 */
public record ReleaseRequest(List<String> keys, String requestId, String expectedVersion) {
}
