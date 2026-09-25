package com.example.starter.race.persistence;

import com.example.starter.race.domain.InspectionGate;
import com.example.starter.race.domain.InspectionResult;

/**
 * equipment_inspection 表行记录（不可变检录历史，复检新插一行）。
 *
 * @param id              自增主键，同毫秒内按 id 判定“最近一条”
 * @param inspectionId    检录记录ID（选手提交的 inspectionKey），全局唯一
 * @param raceId          所属赛事ID
 * @param bib             选手参赛号
 * @param equipmentSerial 器材序列号
 * @param result          检录结果 PASS / FAIL
 * @param validMinutes    提交时配置的有效分钟数快照（1~1440）
 * @param inspectedAt     检录提交时刻，Unix毫秒时间戳
 * @param validUntil      PASS 有效期截止时刻（含端点）；FAIL 为 null
 * @param createdAt       记录入库时间，Unix毫秒时间戳
 */
public record EquipmentInspectionRow(
        long id,
        String inspectionId,
        String raceId,
        String bib,
        String equipmentSerial,
        InspectionResult result,
        int validMinutes,
        long inspectedAt,
        Long validUntil,
        long createdAt
) implements InspectionGate.InspectionView {
}
