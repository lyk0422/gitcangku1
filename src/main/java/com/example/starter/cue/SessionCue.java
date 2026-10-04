package com.example.starter.cue;

/**
 * 排练开始时的提示点冻结快照。
 *
 * @param sessionId 排练场次ID
 * @param seq 冻结快照中的提示点序号
 * @param cueType 提示点类型快照：LIGHT/SOUND
 * @param triggerPhrase 触发口令快照
 * @param durationSeconds 持续秒数快照，1至600
 */
public record SessionCue(long sessionId, int seq, CueType cueType,
                         String triggerPhrase, int durationSeconds) {
}