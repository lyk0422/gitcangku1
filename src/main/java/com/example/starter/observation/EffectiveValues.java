package com.example.starter.observation;

/**
 * 某一版本视角下三个可编辑字段的有效值集合；原始快照与逐版有效值均以此表达。
 *
 * @param location 观测地点
 * @param reading  观测读数（十进制字符串原文）
 * @param note     观测备注
 */
public record EffectiveValues(
        String location,
        String reading,
        String note) {

    /**
     * 由原始观测快照构造。
     */
    public static EffectiveValues of(ObservationSnapshot snapshot) {
        return new EffectiveValues(snapshot.location(), snapshot.reading(), snapshot.note());
    }
}
