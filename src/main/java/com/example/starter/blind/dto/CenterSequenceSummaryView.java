package com.example.starter.blind.dto;

/**
 * 中心序列摘要视图；仅暴露计数，不暴露盲码与处理映射。
 *
 * @param experimentId 实验编号
 * @param centerId     中心编号
 * @param versionNo    序列所属协议版本号
 * @param total        已预留序列总数
 * @param reserved     尚未发放序列数
 * @param issued       已发放序列数
 */
public record CenterSequenceSummaryView(
        String experimentId,
        String centerId,
        int versionNo,
        long total,
        long reserved,
        long issued
) {
}
