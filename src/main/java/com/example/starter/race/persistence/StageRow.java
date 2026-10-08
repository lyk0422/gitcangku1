package com.example.starter.race.persistence;

/**
 * race_stage 表行记录（赛事阶段配置头）。
 *
 * @param raceId    所属赛事ID
 * @param stageNo   阶段序号，从1连续递增
 * @param createdAt 配置时间，Unix毫秒时间戳
 */
public record StageRow(String raceId, int stageNo, long createdAt) {
}
