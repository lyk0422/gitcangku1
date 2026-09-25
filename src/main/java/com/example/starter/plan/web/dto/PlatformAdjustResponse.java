package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 站台长度调整响应：调整后的站台，以及本次被标记 PLATFORM_RISK 的计划快照列表
 * （不下调或无受影响计划时为空列表，历史发布记录不重写）。
 */
public record PlatformAdjustResponse(PlatformView platform, List<RiskSnapshotView> affectedPlans) {
}
