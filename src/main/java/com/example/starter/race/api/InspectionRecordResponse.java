package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单条器材检录历史记录响应（记录不可变）。
 *
 * @param inspectionId    检录记录ID（inspectionKey）
 * @param bib             选手参赛号
 * @param equipmentSerial 器材序列号
 * @param result          检录结果 PASS / FAIL
 * @param validMinutes    提交时配置的有效分钟数快照
 * @param inspectedAt     检录提交时刻，Unix毫秒时间戳
 * @param validUntil      PASS 有效期截止时刻（含端点）；FAIL 为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InspectionRecordResponse(
        String inspectionId,
        String bib,
        String equipmentSerial,
        String result,
        int validMinutes,
        long inspectedAt,
        Long validUntil
) {
}
