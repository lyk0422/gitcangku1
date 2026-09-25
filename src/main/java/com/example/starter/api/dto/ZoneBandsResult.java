package com.example.starter.api.dto;

import java.util.List;

/**
 * 区域高度带配置结果（修改响应与配置查询共用）。
 *
 * @param zoneId        禁飞区标识
 * @param status        区域状态：ACTIVE / REVOKED
 * @param configVersion 当前高度带配置版本
 * @param bands         当前全部高度带（按 bandId 排序）
 */
public record ZoneBandsResult(
        String zoneId,
        String status,
        int configVersion,
        List<AltitudeBandDto> bands) {
}
