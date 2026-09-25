package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 放行门禁状态：聚合当前版本的既有判定条件与新增同行复核门禁。
 *
 * @param measurementKey     测量业务键
 * @param version            当前版本号
 * @param status             当前版本状态：PENDING / RETURNED / RELEASED
 * @param certificateId      当前版本固化的证书 ID
 * @param certificateValid   关联证书当前是否仍有效（未撤销）
 * @param passed             当前版本判定是否合格
 * @param validPass          当前版本是否存在有效 PASS 复核
 * @param validReturn        当前版本是否存在有效 RETURN 复核
 * @param releasable         当前是否同时满足既有判定条件与有效 PASS 复核门禁
 * @param reasons            不可放行的原因码列表；可放行时为空
 */
public record GateStatusResponse(
        String measurementKey,
        int version,
        String status,
        long certificateId,
        boolean certificateValid,
        boolean passed,
        boolean validPass,
        boolean validReturn,
        boolean releasable,
        List<String> reasons) {
}
