package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量放行请求。每批 1～50 条测量键，整批原子生效。
 *
 * @param keys 测量键列表
 */
public record ReleaseRequest(List<String> keys) {
}
