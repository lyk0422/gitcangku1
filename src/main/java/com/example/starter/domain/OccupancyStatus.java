package com.example.starter.domain;

/** 空域占用状态。 */
public enum OccupancyStatus {
    /** 有效占用：登记后尚未让渡（常规与应急共用该状态，类型见 occupancy.kind）。 */
    ACTIVE,
    /**
     * 已让渡给应急走廊：在同一走廊创建事务内由 ACTIVE 转入，
     * 固化原审查版本与让渡原因；不自动恢复，必须重新审查并显式重新申请。
     */
    YIELDED
}
