package com.example.starter.maintenance;

/**
 * 维护窗口状态：ACTIVE 表示生效中（观测提交按其匹配），DELETED 表示已删除；
 * 已被合并快照封存的窗口不允许删除，只能追加更正窗口。
 */
public enum WindowStatus {
    /**
     * 生效中：参与观测提交的维护命中匹配。
     */
    ACTIVE,
    /**
     * 已删除：不再参与匹配；历史快照中固化的引用不受影响。
     */
    DELETED
}
