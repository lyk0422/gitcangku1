package com.example.starter.race.persistence;

/**
 * equipment_binding 表行记录（器材绑定台账，含已释放历史）。
 *
 * @param id              自增主键
 * @param raceId          所属赛事ID
 * @param equipmentSerial 被绑定的器材序列号
 * @param bib             持绑选手参赛号
 * @param inspectionId    产生本次绑定的PASS检录记录ID
 * @param boundAt         绑定生效时间，Unix毫秒时间戳
 * @param releasedAt      绑定释放时间；null 表示仍活跃
 * @param releaseReason   释放原因：RE_INSPECTED/WITHDRAWN/DISQUALIFIED/FINISHED；活跃为 null
 */
public record EquipmentBindingRow(
        long id,
        String raceId,
        String equipmentSerial,
        String bib,
        String inspectionId,
        long boundAt,
        Long releasedAt,
        String releaseReason
) {
}
