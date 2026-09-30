package com.example.starter.cue.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 编排版本视图，含版本内全部提示点（按序号升序）。
 *
 * @param id        版本 ID
 * @param playCode  剧目编码
 * @param versionNo 剧目内递增版本号
 * @param cues      提示点列表
 * @param createdAt 版本创建时间（Asia/Shanghai）
 */
public record VersionResponse(
        Long id,
        String playCode,
        Integer versionNo,
        List<CuePointView> cues,
        LocalDateTime createdAt) {

    /**
     * 单个提示点视图。
     *
     * @param seqNo           场次内序号
     * @param cueType         提示点类型
     * @param triggerWord     触发口令
     * @param durationSeconds 持续秒数，单位：秒
     */
    public record CuePointView(
            Integer seqNo,
            String cueType,
            String triggerWord,
            Integer durationSeconds) {
    }
}
