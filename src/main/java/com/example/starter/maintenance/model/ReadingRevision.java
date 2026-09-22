package com.example.starter.maintenance.model;

import java.time.Instant;

/**
 * 读数修订历史条目：修订只改变累计分钟，不改采样时刻。
 *
 * @param revisionNo         修订号，从1开始单调递增
 * @param accumulatedMinutes 该修订版本的累计工时，单位分钟
 * @param revisedAt          修订发生时刻（UTC）
 */
public record ReadingRevision(int revisionNo, long accumulatedMinutes, Instant revisedAt) {
}
