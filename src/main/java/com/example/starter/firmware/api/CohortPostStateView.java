package com.example.starter.firmware.api;

/**
 * 迁移预览队列后态视图：按完整后态计算的队列规模与配额上限。
 */
public record CohortPostStateView(long cohortId, String code, int currentSize, int postSize,
                                  int deviceCap, int regionQuota, int grayPercent,
                                  long totalAssigned) {
}
