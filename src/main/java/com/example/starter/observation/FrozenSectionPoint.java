package com.example.starter.observation;

/**
 * 断面成果中冻结的源断面点：创建成果时的观测标识、版本与完整坐标组，
 * 之后源观测变更不回写、不重算。
 *
 * @param observationId 源观测记录唯一标识
 * @param version       创建成果时核验的源当前版本号
 * @param lateralMm     横向坐标，整数毫米
 * @param elevationMm   高程，整数毫米
 */
public record FrozenSectionPoint(
        String observationId,
        int version,
        int lateralMm,
        int elevationMm) {
}
