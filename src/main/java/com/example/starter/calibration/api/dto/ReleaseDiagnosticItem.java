package com.example.starter.calibration.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 放行诊断：单条测量的放行评估明细。仅诊断不改状态，用于放行失败响应、重算后整批重评与诊断查询。
 *
 * @param measurementKey   测量标识（稳定按字典序输出）
 * @param versionNo      评估的测量版本号
 * @param status         当前状态
 * @param releaseValue   放行判定使用的值（补偿后测量值；无环境为基础计算值）字符串
 * @param lowerLimit     合格下限字符串
 * @param upperLimit     合格上限字符串
 * @param uncertainty   不确定度字符串；未评估为 null
 * @param uncertaintyLimit 批次不确定度上限字符串；未启用为 null
 * @param hasEnvironment 是否记录环境
 * @param passable      结构与环境门禁综合是否可放行
 * @param reasons       不可放行原因码（稳定排序），如 ALREADY_RELEASED、NOT_PENDING、NOT_PASSED、
 *                      CERTIFICATE_REVOKED、SAME_ACTOR、MISSING_ENVIRONMENT、
 *                      OUT_OF_SPEC_AFTER_COMP、UNCERTAINTY_EXCEEDED、MEASUREMENT_NOT_FOUND
 */
public record ReleaseDiagnosticItem(
        String measurementKey,
        Integer versionNo,
        String status,
        String releaseValue,
        String lowerLimit,
        String upperLimit,
        String uncertainty,
        String uncertaintyLimit,
        boolean hasEnvironment,
        boolean passable,
        List<String> reasons) {
}
