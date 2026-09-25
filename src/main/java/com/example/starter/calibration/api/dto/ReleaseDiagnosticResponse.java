package com.example.starter.calibration.api.dto;

import java.util.List;

import com.example.starter.calibration.api.ItemFailure;

/**
 * 放行诊断响应（不落库、不绑定、不改变状态）。按与正式放行相同的门禁逐项给出原因：
 * 不存在、未待放行、不合格、证书到期/撤销、补偿系数或不确定度版本不可追溯、
 * singleBatchOnly 已绑定其他批次、放行人与提交人相同等。
 *
 * @param releasable 全部条目是否均满足放行门禁（true 时正式放行在无并发变化下可成功）
 * @param items      逐项诊断结果，reasons 为空表示该项当前可放行
 */
public record ReleaseDiagnosticResponse(
        boolean releasable,
        List<ItemFailure> items) {
}
