package com.example.starter.race.api;

/**
 * 器材当前绑定清单中的一条：器材序列号与其绑定的未完赛选手。
 *
 * @param equipmentSerial 器材序列号
 * @param bib             当前绑定选手参赛号
 * @param inspectionId    产生绑定的 PASS 检录记录键
 * @param boundAt         最近绑定时间，Unix毫秒时间戳
 */
public record EquipmentBindingEntryResponse(
        String equipmentSerial,
        String bib,
        String inspectionId,
        long boundAt
) {
}
