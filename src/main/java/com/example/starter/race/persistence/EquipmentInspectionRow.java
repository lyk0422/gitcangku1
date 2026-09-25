package com.example.starter.race.persistence;

import com.example.starter.race.domain.InspectionResult;

/**
 * equipment_inspection 不可变历史表行记录（只追加，不更新不删除）。
 *
 * @param inspectionId    检录记录业务键（inspectionKey），全局唯一
 * @param raceId          所属赛事ID
 * @param bib             被检录选手参赛号
 * @param equipmentSerial 器材序列号
 * @param result          检录结果 PASS / FAIL
 * @param validMinutes    本次检录快照的有效分钟数（1~1440）
 * @param inspectedAt     检录提交时刻，Unix毫秒时间戳
 * @param validUntil      PASS 有效截止时刻；FAIL 为 null
 * @param createdAt       落库时间，Unix毫秒时间戳
 */
public record EquipmentInspectionRow(
        String inspectionId,
        String raceId,
        String bib,
        String equipmentSerial,
        InspectionResult result,
        int validMinutes,
        long inspectedAt,
        Long validUntil,
        long createdAt
) {
}
