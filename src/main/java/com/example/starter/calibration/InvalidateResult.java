package com.example.starter.calibration;

import java.util.List;

/**
 * 作废结果，含作废影响到的绑定及其处理后状态。
 *
 * @param calibration 作废后的校准
 * @param impactedBindings 受影响的绑定（未开始转待重配，已完成保留旧依据并标记作废）
 */
public record InvalidateResult(
        CalibrationView calibration,
        List<BindingView> impactedBindings) {
}