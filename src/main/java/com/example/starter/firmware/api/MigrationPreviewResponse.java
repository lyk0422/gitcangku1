package com.example.starter.firmware.api;

import java.util.List;

/**
 * 迁移预览结果：按完整后态计算各受影响队列规模、灰度上限与区域配额占用，不写数据。
 *
 * @param feasible    完整后态是否全部合法；false 时 violations 给出全部越界原因
 * @param violations  完整后态越界说明（设备上限/灰度百分比/区域配额/版本/已确认安装等）
 * @param cohorts     受影响队列迁移前、后的规模与配额占用
 */
public record MigrationPreviewResponse(String migrationKey, long releaseId, boolean feasible,
                                       List<String> violations, List<CohortAfterState> cohorts) {

    /**
     * 单个受影响队列的完整后态。
     */
    public record CohortAfterState(long cohortId, String cohortCode, String firmwareVersion,
                                   String regionCode, int deviceCap, int canaryPercent,
                                   int beforeCount, int afterCount, int canaryLimit,
                                   int regionQuota, int regionUsedBefore, int regionUsedAfter,
                                   boolean deviceCapOk, boolean canaryOk, boolean regionQuotaOk) {
    }
}
