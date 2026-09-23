package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 失效影响闭包：受影响标准器版本（按 standardId 升序）与受影响测量校准记录（按 measurementKey 升序），
 * 稳定排序，可序列化为规范快照用于漂移检测与同参重放。
 *
 * @param standards    受影响标准器版本列表
 * @param measurements 受影响测量校准记录列表
 */
public record InvalidationClosureDto(
        List<AffectedStandardDto> standards,
        List<AffectedMeasurementDto> measurements) {
}
