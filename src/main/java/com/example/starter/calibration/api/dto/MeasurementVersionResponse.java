package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 测量版本血缘条目（单版本快照）。
 *
 * @param versionNo           版本号
 * @param current             是否为当前生效版本
 * @param standardId          标准器 ID
 * @param certificateId       证书 ID
 * @param certificateVersion  证书版本
 * @param coeffA              补偿系数 a 快照
 * @param offsetB             补偿偏移 b 快照
 * @param uncertainty         标准器标准不确定度快照
 * @param uncertaintyVersion  不确定度版本
 * @param computedValue       该版本未舍入计算值
 * @param expandedUncertainty 该版本扩展不确定度（k=2）
 * @param passed              该版本合格判定
 * @param referenceKey        该版本 referenceKey 指纹
 * @param createdAt           版本生成时间（UTC）
 */
public record MeasurementVersionResponse(
        int versionNo,
        boolean current,
        String standardId,
        long certificateId,
        String certificateVersion,
        String coeffA,
        String offsetB,
        String uncertainty,
        String uncertaintyVersion,
        String computedValue,
        String expandedUncertainty,
        boolean passed,
        String referenceKey,
        Instant createdAt) {
}
