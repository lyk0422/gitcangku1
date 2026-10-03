package com.example.starter.custody;

/**
 * 批次状态:ACTIVE=叶子批次,可拆分、可移交;SPLIT=已拆分,仅保留为来源节点。
 */
public enum BatchStatus {
    ACTIVE,
    SPLIT
}