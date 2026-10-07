package com.example.starter.plan.model;

/**
 * 已发布计划占用的实际状态：NOT_ENTERED 未进入；INSIDE 已进入未驶出（同区段至多一个）；
 * EXITED 已驶出，轨迹保留不倒改计划时隙。
 */
public enum ActualStatus {
    NOT_ENTERED,
    INSIDE,
    EXITED
}
