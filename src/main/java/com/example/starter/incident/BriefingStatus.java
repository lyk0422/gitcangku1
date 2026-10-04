package com.example.starter.incident;

/**
 * 交接简报状态：ACTIVE 可回读、可刷新；FROZEN 交接成功后冻结。
 */
public enum BriefingStatus {

    /** 当前有效；仅最新版本保持 ACTIVE，交接成功前可回读、可被刷新取代。 */
    ACTIVE,

    /** 交接成功后冻结，简报内容与回读人不再变更，仅作证据保留。 */
    FROZEN
}