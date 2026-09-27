package com.example.starter.evidence.dto;

/**
 * 冻结摘要中的逐件摘要项：仅保留最核心的件次、位置、链版本与封签结论。
 *
 * @param position        规范排序后的件次（从 0 开始）
 * @param evidenceKey     证物业务键
 * @param custodianAtAsOf 截至时刻保管人（位置）
 * @param chainVersion    截至时刻链版本
 * @param sealIntactAtAsOf 截至时刻封签是否正常
 */
public record FrozenItemDigest(
        int position,
        String evidenceKey,
        String custodianAtAsOf,
        int chainVersion,
        boolean sealIntactAtAsOf) {
}
