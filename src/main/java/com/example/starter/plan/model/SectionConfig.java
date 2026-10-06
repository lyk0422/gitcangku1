package com.example.starter.plan.model;

/**
 * 单线区段换向配置：相邻反向占用须满足清空间隔，同向仅遵守互斥。
 *
 * @param id                主键
 * @param sectionId         区段 ID，全局唯一
 * @param directionForward  正向行车方向标识
 * @param directionBackward 反向行车方向标识
 * @param clearanceSeconds  换向清空间隔（整秒，1 至 600）
 * @param version           配置版本，从 1 起，每次更新加一
 */
public record SectionConfig(long id, String sectionId, String directionForward,
                            String directionBackward, int clearanceSeconds, int version) {

    /**
     * 判断给定方向是否为配置允许的正/反方向之一。
     */
    public boolean allows(String direction) {
        return directionForward.equals(direction) || directionBackward.equals(direction);
    }
}
