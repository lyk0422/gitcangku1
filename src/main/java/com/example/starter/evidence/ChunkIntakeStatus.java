package com.example.starter.evidence;

/**
 * 分块入库接收状态。
 * RECEIVING 接收中（可补块、完成或放弃）；COMPLETED 已完成（已生成 SEALED 证物，块清单只读）；
 * ABANDONED 已放弃（摘要错等场景，证物键可由新记录复用）。
 */
public enum ChunkIntakeStatus {
    RECEIVING,
    COMPLETED,
    ABANDONED
}
