package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁文件中单个名称对应的精确版本，含锁定时固化的摘要与见证。
 *
 * @param checksumAlgorithm 锁定时固化的摘要算法
 * @param checksumDigest    锁定时固化的内容校验摘要
 * @param attesters         锁定时固化的有效见证人，按字典序排列
 * @param attestationCount  锁定时固化的有效见证人数
 */
public record LockEntryResponse(
        String name,
        int version,
        String checksumAlgorithm,
        String checksumDigest,
        List<String> attesters,
        int attestationCount) {
}
