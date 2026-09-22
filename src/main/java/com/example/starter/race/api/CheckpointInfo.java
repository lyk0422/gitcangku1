package com.example.starter.race.api;

/**
 * 检查点信息：编码与顺序（从1开始连续递增）。
 *
 * @param checkpointCode 检查点编码，赛事内唯一
 * @param seq            检查点顺序，从1开始
 */
public record CheckpointInfo(String checkpointCode, int seq) {
}
