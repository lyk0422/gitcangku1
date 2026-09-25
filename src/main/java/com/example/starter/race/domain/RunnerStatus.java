package com.example.starter.race.domain;

/**
 * 参赛者参赛状态：
 * ACTIVE-有效，可被冲线证据引用并参与排名；
 * WITHDRAWN-已退赛，不参与排名，裁决证据时作为“候选人失效”处理（422）。
 */
public enum RunnerStatus {
    ACTIVE,
    WITHDRAWN
}
