package com.example.starter.race.api;

import com.example.starter.race.domain.InspectionResult;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 检录历史中的一条不可变记录。
 *
 * @param inspectionId    检录记录键
 * @param equipmentSerial 器材序列号
 * @param result          检录结果 PASS / FAIL
 * @param validMinutes    本次检录有效分钟数；FAIL 为 null
 * @param inspectedAt     检录提交时刻，Unix毫秒时间戳
 * @param validUntil      PASS 有效截止时刻；FAIL 为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InspectionHistoryEntryResponse(
        String inspectionId,
        String equipmentSerial,
        InspectionResult result,
        Integer validMinutes,
        long inspectedAt,
        Long validUntil
) {
}
