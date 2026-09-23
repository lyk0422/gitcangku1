package com.example.starter.observation;

/**
 * 关联观测簇成员角色。
 */
public enum BundleMemberRole {
    /**
     * 正常成员：建簇时为存活观测，可直接提供候选值。
     */
    ACTIVE,
    /**
     * 待恢复成员：建簇时为删除墓碑，不能直接提供候选值，只能在联合裁决中显式恢复。
     */
    PENDING_RESTORE
}
