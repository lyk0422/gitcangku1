package com.example.starter.observation.model;

/**
 * 写操作类型，同时作为幂等记录的操作维度。
 */
public enum OperationType {
    CREATE,
    OFFLINE_SUBMIT,
    DELETE
}
