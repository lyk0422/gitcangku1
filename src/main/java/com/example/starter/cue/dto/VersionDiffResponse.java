package com.example.starter.cue.dto;

import java.util.List;

/**
 * 同一剧目两个编排版本之间的差异视图（按序号对齐）。
 *
 * @param playCode       剧目编码
 * @param fromVersionNo  基准版本号
 * @param toVersionNo    对比版本号
 * @param added          对比版本新增的提示点（基准中不存在该序号）
 * @param removed        基准中被删除的提示点（对比版本不存在该序号）
 * @param changed        两版本均存在但内容变化的提示点
 * @param unchangedCount 两版本完全一致的提示点数量
 */
public record VersionDiffResponse(
        String playCode,
        int fromVersionNo,
        int toVersionNo,
        List<VersionResponse.CuePointView> added,
        List<CuePair> removed,
        List<CuePair> changed,
        int unchangedCount) {

    /**
     * 同一序号在两个版本中的提示点对；某一侧不存在时对应字段为 null。
     *
     * @param seqNo 提示点序号
     * @param from  基准版本提示点，新增场景为 null
     * @param to    对比版本提示点，删除场景为 null
     */
    public record CuePair(
            Integer seqNo,
            VersionResponse.CuePointView from,
            VersionResponse.CuePointView to) {
    }
}
