package com.example.starter.plan.model;

/**
 * 容量交换单状态：PREVIEW 预览单（尚未激活，占用未改变）；
 * ACTIVE 已激活（占用已整体替换，快照不可变）。
 */
public enum SwapStatus {
    PREVIEW,
    ACTIVE
}
