package com.example.starter.race.persistence;

/**
 * equipment_binding 当前绑定表行记录：同一(赛事,器材序列号)最多一行。
 *
 * @param raceId       所属赛事ID
 * @param equipmentSerial 器材序列号，赛事内唯一
 * @param bib          当前绑定的未完赛选手参赛号
 * @param inspectionId 产生绑定的 PASS 检录记录键
 * @param boundAt      最近绑定时间，Unix毫秒时间戳
 */
public record EquipmentBindingRow(
        String raceId,
        String equipmentSerial,
        String bib,
        String inspectionId,
        long boundAt
) {
}
