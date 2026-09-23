package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 工时表更换链只读视图：完整链条及跨表重算版本记录。
 *
 * @param equipmentId   所属设备标识
 * @param activeMeterKey 当前 ACTIVE 工时表标识
 * @param meters        更换链全部工时表（按序号升序）
 * @param recomputes    跨表重算记录（按重算序号升序）
 */
public record ChainResponse(
        String equipmentId,
        String activeMeterKey,
        List<MeterResponse> meters,
        List<RecomputeView> recomputes) {
}
