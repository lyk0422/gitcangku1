package com.example.starter.race.api;

/**
 * 选手起跑结果。
 *
 * @param startId   起跑记录键
 * @param bib       起跑选手参赛号
 * @param startedAt 起跑提交时刻，Unix毫秒时间戳
 */
public record StartResponse(String startId, String bib, long startedAt) {
}
