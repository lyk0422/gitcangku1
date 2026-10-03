package com.example.starter.model;

import java.time.Instant;

/**
 * 登轮点变更历史行记录。
 *
 * @param id           主键
 * @param segmentId    航段ID
 * @param oldPoint     修改前登轮点
 * @param newPoint     修改后登轮点
 * @param changedAtUtc 修改时间（UTC）
 */
public record BoardingPointChangeRow(long id, long segmentId, String oldPoint, String newPoint,
                                     Instant changedAtUtc) {
}