package com.example.starter.api.dto;

import java.util.List;

/**
 * 区域高度带配置查询视图。
 *
 * @param zoneId      禁飞区标识
 * @param status      区域状态：ACTIVE / REVOKED
 * @param zoneVersion 区域当前配置版本（下次配置时作为 expectedVersion）
 * @param bands       该区域登记的全部高度带
 */
public record ZoneBandsView(
        String zoneId,
        String status,
        int zoneVersion,
        List<BandResultDto> bands) {
}
