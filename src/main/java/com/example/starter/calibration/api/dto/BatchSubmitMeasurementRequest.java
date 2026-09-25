package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量测量提交请求。整批先按最终引用预校验，全部通过后才写入；任一条失败整批不落库。
 *
 * @param batchId     客户端提供的批量提交幂等键；相同键重放返回首次结果，同键不同载荷 409
 * @param submittedBy 提交人（作用于整批；各条目也可携带，缺省用此值）
 * @param items       测量条目，1～50 条
 */
public record BatchSubmitMeasurementRequest(
        String batchId,
        String submittedBy,
        List<SubmitMeasurementRequest> items) {
}
