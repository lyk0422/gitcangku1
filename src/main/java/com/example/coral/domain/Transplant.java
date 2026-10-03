package com.example.coral.domain;

import java.time.Instant;

/**
 * 移植登记：固化来源苗圃、样方与UTC时刻；死亡后记录保留。
 *
 * @param id 移植记录主键
 * @param requestId 幂等请求编号
 * @param plantCode 植株编号，项目内唯一
 * @param nursery 来源苗圃
 * @param quadratId 所属样方ID，登记后不可变更
 * @param status 当前状态：PENDING待复核/ALIVE存活/DEAD死亡
 * @param transplantedAt 移植登记时刻（UTC）
 */
public record Transplant(long id, String requestId, String plantCode, String nursery,
        long quadratId, PlantStatus status, Instant transplantedAt) {
}