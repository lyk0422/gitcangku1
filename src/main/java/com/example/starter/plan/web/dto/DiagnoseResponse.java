package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 限速诊断响应：指定区段与 UTC 左闭右开时段内的生效限速裁决结果。
 * effectiveSpeedKmh 为全部生效限速令的最低速度；无生效限速令时为 null。
 * 诊断查询为只读操作，不改变任何状态。
 */
public record DiagnoseResponse(String sectionId, Instant startUtc, Instant endUtc,
                               Integer effectiveSpeedKmh, List<DiagnoseContributor> contributors) {
}
