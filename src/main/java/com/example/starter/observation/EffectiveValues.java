package com.example.starter.observation;

/**
 * 三个可编辑字段在某一附录版本上的有效值；由原始快照依次叠加字段差异得到。
 *
 * @param location 观测地点有效值
 * @param reading  观测读数有效值（十进制原文）
 * @param note     观测备注有效值
 */
public record EffectiveValues(
        String location,
        String reading,
        String note) {

    /**
     * 由只读观测快照构造初始有效值（附录版本 0）。
     */
    public static EffectiveValues of(ObservationSnapshot snapshot) {
        return new EffectiveValues(snapshot.location(), snapshot.reading(), snapshot.note());
    }

    /**
     * 读取指定字段的当前有效值。
     */
    public String get(String field) {
        return switch (field) {
            case "location" -> location;
            case "reading" -> reading;
            case "note" -> note;
            default -> throw new IllegalArgumentException("unknown field: " + field);
        };
    }
}
