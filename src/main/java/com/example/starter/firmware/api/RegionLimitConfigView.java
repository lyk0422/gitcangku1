package com.example.starter.firmware.api;

import java.util.List;

/**
 * 区域上限配置视图：发布单当前版本与全量区域上限。
 */
public record RegionLimitConfigView(long releaseId, int version, List<RegionLimitItem> limits) {
}
