package com.example.starter.cue.domain;

/**
 * 版本内提示点 PO。
 *
 * @param id              主键
 * @param versionId       所属版本 ID
 * @param seqNo           场次内序号，从 1 开始连续，同版本唯一
 * @param cueType         提示点类型（LIGHT 灯光 / SOUND 音效）
 * @param triggerWord     触发口令
 * @param durationSeconds 持续秒数，单位：秒，范围 1 至 600
 */
public record CueItem(
        Long id,
        Long versionId,
        Integer seqNo,
        String cueType,
        String triggerWord,
        Integer durationSeconds) {
}
