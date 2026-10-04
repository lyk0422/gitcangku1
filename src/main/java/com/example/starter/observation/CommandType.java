package com.example.starter.observation;

/**
 * 批次消息中观测业务请求的类型：创建（CREATE）或离线三方合并（MERGE）。
 */
public enum CommandType {
    CREATE,
    MERGE
}