package com.example.starter.calibration.model;

/**
 * 重新放行逐项血缘：新批次每个位置记录来源批次与实际采用测量。
 *
 * @param id                  血缘记录 ID（自增）
 * @param newBatchId          重新放行生成的新批次 ID
 * @param sourceBatchId       来源（被复核驳回的）旧批次 ID
 * @param position            批次内位置（从 1 开始），新旧批次一一对应
 * @param sourceMeasurementId 旧批次该位置的原始测量记录 ID
 * @param usedMeasurementId   新批次实际采用测量记录 ID（驳回项为修订行，其余为原行）
 * @param version             重新放行提交并校验通过的版本快照
 * @param revised             该位置是否使用了修订
 */
public record BatchLineage(
        long id,
        String newBatchId,
        String sourceBatchId,
        int position,
        long sourceMeasurementId,
        long usedMeasurementId,
        int version,
        boolean revised) {
}
