package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 放行门禁状态：既有判定条件与复核门禁的当前评估结果。
 *
 * @param measurementKey     测量键
 * @param revision           当前修订版本号
 * @param status             测量状态：PENDING / NEEDS_REVISION / RELEASED
 * @param passed             是否合格（既有判定）
 * @param certificateRevoked 关联证书是否已撤销
 * @param effectivePassReview 当前版本的有效 PASS 复核键；无则为 null
 * @param releasable         当前是否满足全部放行条件
 * @param reasons            不满足放行条件的原因码；可放行为空列表
 */
public record GateStatusResponse(
        String measurementKey,
        int revision,
        String status,
        boolean passed,
        boolean certificateRevoked,
        String effectivePassReview,
        boolean releasable,
        List<String> reasons) {
}
