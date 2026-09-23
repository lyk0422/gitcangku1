package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 工时表更换成功快照：同 replacementKey 同参重放时原样返回本快照。
 *
 * @param replacementKey    更换请求标识
 * @param equipmentId       所属设备标识
 * @param equipmentVersion  更换后的设备版本号
 * @param closedMeter       被关闭的旧表视图
 * @param activeMeter       新启用的 ACTIVE 表视图
 * @param chain             更换成功后该设备完整更换链（按序号升序）
 */
public record ReplacementResponse(
        String replacementKey,
        String equipmentId,
        long equipmentVersion,
        MeterResponse closedMeter,
        MeterResponse activeMeter,
        List<MeterResponse> chain) {
}
