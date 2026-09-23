package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 版本化批量放行请求。每批 1～50 个不同 measurementKey 及其 revision，整批原子生效。
 *
 * @param items 版本化放行项列表
 */
public record VersionedReleaseRequest(List<VersionedReleaseItem> items) {
}
