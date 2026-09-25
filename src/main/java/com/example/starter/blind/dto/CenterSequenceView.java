package com.example.starter.blind.dto;

/**
 * 中心序列视图：中心在某协议版本下预留的独立盲码序列容量与消耗进度；不暴露未使用盲码。
 *
 * @param experimentId 实验编号
 * @param centerId     中心编号
 * @param version      协议版本号，该序列永远归属此版本
 * @param total        预留盲码总条数（= 生效时该中心剩余容量）
 * @param consumed     已消耗条数（已分配）
 * @param available    尚可消耗条数 = total - consumed
 */
public record CenterSequenceView(
        String experimentId,
        String centerId,
        int version,
        long total,
        long consumed,
        long available
) {
}
