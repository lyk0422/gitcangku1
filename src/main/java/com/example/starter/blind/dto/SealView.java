package com.example.starter.blind.dto;

import java.util.List;

/**
 * 随机表封存视图（明细/历史元素）：只含摘要、容量、处理代码集合与时间，
 * 不含任何逐序号处理代码（具体序列）。
 *
 * @param sealId         封存编号
 * @param experimentId   实验编号
 * @param blockNo        区组号
 * @param versionNo      随机表版本号，从1开始
 * @param capacity       本版本容量，单位：个序号
 * @param treatmentCodes 处理代码集合，按字典序排列，不暴露逐序号代码
 * @param tableDigest    随机表摘要（SHA-256 十六进制）
 * @param predecessorId  前一封存版本编号；首次封存为 null
 * @param status         SEALED=可分配；CLOSED=未分配余量已关闭
 * @param allocated      本版本已分配序号数（含已退组，序号不释放）
 * @param remaining      本版本当前可分配名额；CLOSED 版本恒为 0
 * @param abandoned      关闭余量时放弃的未分配名额；SEALED 版本恒为 0
 * @param sealedBy       封存操作者编号
 * @param sealedAt       封存生成时刻，Unix 毫秒，UTC
 * @param closedAt       余量关闭时刻，Unix 毫秒，UTC；null 表示未关闭
 */
public record SealView(
        String sealId,
        String experimentId,
        int blockNo,
        int versionNo,
        int capacity,
        List<String> treatmentCodes,
        String tableDigest,
        String predecessorId,
        String status,
        int allocated,
        int remaining,
        int abandoned,
        String sealedBy,
        long sealedAt,
        Long closedAt
) {
}
