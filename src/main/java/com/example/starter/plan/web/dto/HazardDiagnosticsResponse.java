package com.example.starter.plan.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 编组诊断：以当前编组与完整区段路径对照当前版本规则/区段配置的试校验结果，
 * 不改变任何状态；conflicts 为结构化冲突明细（含实际值、要求值与差额）。
 */
public record HazardDiagnosticsResponse(String scheduleKey, int ruleVersion, int sectionVersion,
                                        boolean valid, List<HazardCarView> formation,
                                        List<String> sections, List<Map<String, Object>> conflicts) {
}
