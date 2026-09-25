package com.example.starter.race.persistence;

import com.example.starter.race.domain.WaveAssignmentView;

/**
 * 波次归属与起跑时刻的关联视图（wave_entrant JOIN wave），供净计时计算使用。
 *
 * @param bib         参赛者参赛号
 * @param waveKey     所属波次唯一键
 * @param waveStartMs 波次 UTC 起跑时刻，Unix 毫秒时间戳
 */
public record WaveAssignment(
        String bib,
        String waveKey,
        long waveStartMs
) implements WaveAssignmentView {
}
