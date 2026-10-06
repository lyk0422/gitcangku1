package com.example.starter.observation;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号，从 1 开始
 * @param location      观测地点（可编辑字段）；删除墓碑版本为 null
 * @param reading       观测读数，十进制字符串，最多三位小数，比较按数值；删除墓碑版本为 null
 * @param note          观测备注（可编辑字段）；删除墓碑版本为 null
 * @param dx            二维位移 X 分量（米，十进制字符串，最多三位小数，比较按数值）；
 *                      与 dy/w 组成完整字段组，null 表示无位移；删除墓碑版本为 null
 * @param dy            二维位移 Y 分量（米，十进制字符串，最多三位小数）；null 表示无位移
 * @param w             边长权重（米，正数十进制字符串，最多三位小数）；null 表示无位移
 * @param deleted       是否为删除墓碑：true 时业务字段无意义
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        String location,
        String reading,
        String note,
        String dx,
        String dy,
        String w,
        boolean deleted) {

    /**
     * 判断该快照是否携带完整位移字段组（dx/dy/w 全部非空）。
     */
    public boolean hasDisplacement() {
        return dx != null && dy != null && w != null;
    }
}
