package com.example.starter.race.domain;

/**
 * 参赛者波次归属视图：参赛号与其所属波次的起跑时刻。
 * 同一参赛者至多属于一个波次（由数据库主键与服务校验共同保证）。
 */
public interface WaveAssignmentView {

    /** 参赛号。 */
    String bib();

    /** 所属波次唯一键。 */
    String waveKey();

    /** 波次 UTC 起跑时刻，Unix 毫秒时间戳。 */
    long waveStartMs();
}
