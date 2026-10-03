package com.example.starter.cue.domain;

/**
 * 排练开始时冻结的提示点快照，之后不随版本修改变化。
 *
 * @param id              快照主键
 * @param sessionId       所属场次 ID
 * @param seq             冻结时的提示点序号，场次内唯一
 * @param cueType         提示点类型：LIGHT 灯光 / SOUND 音效
 * @param triggerPhrase   触发口令
 * @param durationSeconds 持续秒数，1 至 600
 */
public record SessionCue(long id, long sessionId, int seq, String cueType,
                         String triggerPhrase, int durationSeconds) {
}