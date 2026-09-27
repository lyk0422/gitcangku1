package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单目标策略与快照明细视图（只读）。策略、字典版本、解析时刻与快照设备
 * 均为创建时固化的业务证据，后续标签修订不回写本视图。
 */
public record ReleaseTargetView(long releaseId, List<String> includeAll, List<String> excludeAny,
                                List<String> includeAny, long tagDictVersion, String resolvedAtUtc,
                                int targetCount, List<String> devices) {
}
