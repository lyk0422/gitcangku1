package com.example.starter.cue.domain;

/**
 * 冻结快照中的提示点 PO；排练开始后独立于编排版本存在。
 *
 * @param id              主键
 * @param sessionId       所属场次 ID
 * @param seqNo           提示点序号
 * @param cueType         提示点类型
 * @param triggerWord     触发口令
 * @param durationSeconds 持续秒数，单位：秒
 */
public record CueSnapshot(
        Long id,
        Long sessionId,
        Integer seqNo,
        String cueType,
        String triggerWord,
        Integer durationSeconds) {
}
