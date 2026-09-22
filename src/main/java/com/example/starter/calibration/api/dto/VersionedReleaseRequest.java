package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 版本化批量放行请求。每批 1～50 个互不相同的 measurementKey，每项显式指定 revision。
 *
 * @param items 放行项列表
 */
public record VersionedReleaseRequest(List<VersionedReleaseItem> items) {
}
