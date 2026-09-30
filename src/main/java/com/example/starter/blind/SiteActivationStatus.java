package com.example.starter.blind;

/**
 * 中心启用申请（配置版本）状态；历史记录一经写入不可删除，重复版本再启用不改变历史。
 */
public enum SiteActivationStatus {
    /** 待确认：负责人与独立复核人尚未完成同一版本的双人确认。 */
    PENDING,
    /** 已启用：双人确认齐备并一次性冻结区组名额快照。 */
    ACTIVATED
}
