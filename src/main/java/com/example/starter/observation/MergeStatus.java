package com.example.starter.observation;

/**
 * 观测记录的归并生命周期状态。
 *
 * <ul>
 *   <li>{@link #ACTIVE}：活跃记录，可作为重复观测簇候选成员；</li>
 *   <li>{@link #MERGED}：已作为成员归并入某个簇，记录保留只读、历史可查，
 *       但拒绝后续离线三方合并、冲突解决、删除或恢复。</li>
 * </ul>
 */
public enum MergeStatus {
    ACTIVE,
    MERGED;

    /**
     * 解析持久化字符串；非法值回退为 null（由调用方按数据损坏处理）。
     */
    public static MergeStatus fromValue(String value) {
        if (value == null) {
            return ACTIVE;
        }
        return MergeStatus.valueOf(value);
    }
}
