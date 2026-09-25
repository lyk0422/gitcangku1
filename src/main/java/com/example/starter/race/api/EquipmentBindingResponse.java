package com.example.starter.race.api;

/**
 * 单条活跃器材绑定。
 *
 * @param equipmentSerial 器材序列号
 * @param bib             当前持绑选手参赛号
 * @param inspectionId    产生绑定的 PASS 检录记录ID
 * @param boundAt         绑定生效时间，Unix毫秒时间戳
 */
public record EquipmentBindingResponse(
        String equipmentSerial,
        String bib,
        String inspectionId,
        long boundAt
) {
}
