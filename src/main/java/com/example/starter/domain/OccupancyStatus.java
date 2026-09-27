package com.example.starter.domain;

/**
 * 空域占用状态。
 */
public enum OccupancyStatus {

    /** 有效占用。 */
    ACTIVE,

    /** 已被应急走廊强制让渡（终态，不自动恢复，须重新审查并显式重新申请）。 */
    YIELDED
}
