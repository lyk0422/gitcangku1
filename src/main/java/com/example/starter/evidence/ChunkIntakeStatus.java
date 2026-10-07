package com.example.starter.evidence;

/**
 * 分块接收状态。
 * RECEIVING 待接收：入库人可乱序补块、显式完成或放弃，其他入口不可操作；
 * SEALED 已完成：已创建同键 SEALED 证物与只读块清单，不可再补块；
 * ABANDONED 已放弃（终态）：摘要错误等情况下放弃该接收，只能另建新记录。
 */
public enum ChunkIntakeStatus {
    RECEIVING,
    SEALED,
    ABANDONED
}
