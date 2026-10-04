package com.example.starter.plan.model;

/**
 * 接续状态：ACTIVE 生效中参与保障判定；CANCELLED 已取消不再判定，
 * 取消只改变当前状态，不改写已固化的历史事件。
 */
public enum ConnectionStatus {
    ACTIVE,
    CANCELLED
}