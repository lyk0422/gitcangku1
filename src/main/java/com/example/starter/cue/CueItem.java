package com.example.starter.cue;

/**
 * 版本内的提示点明细。
 *
 * @param id 提示点ID，未持久化时为null
 * @param versionId 所属版本ID
 * @param seq 场次内递增序号，从1开始
 * @param cueType 提示点类型：LIGHT灯光/SOUND音效
 * @param triggerPhrase 触发口令
 * @param durationSeconds 持续秒数，有效范围1至600
 */
public record CueItem(Long id, long versionId, int seq, CueType cueType,
                      String triggerPhrase, int durationSeconds) {
}