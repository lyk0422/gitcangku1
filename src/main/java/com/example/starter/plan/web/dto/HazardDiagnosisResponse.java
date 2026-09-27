package com.example.starter.plan.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 危险品隔离诊断结果（只读）：对计划当前完整编组与完整区段路径执行与发布完全一致的校验，
 * 不发布、不修改任何状态。
 *
 * @param valid           校验是否通过（无任何冲突）
 * @param configVersionNo 诊断时刻生效配置版本号；无生效配置时为 null（语义：无任何限制）
 * @param details         全部冲突明细（数量/间隔冲突含实际值、要求值与差额），按类型与业务键稳定排序
 */
public record HazardDiagnosisResponse(boolean valid, Integer configVersionNo,
                                      List<Map<String, Object>> details) {
}
