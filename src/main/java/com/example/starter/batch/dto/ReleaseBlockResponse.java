package com.example.starter.batch.dto;

import java.util.List;

/**
 * 放行持续门禁检查结果：
 * releaseBlocked=true 时存在未裁决 MAJOR 偏差，放行返回 422 且 openMajorExcursionKeys 给出偏差标识；
 * minorQualityConfirmationPending=true 表示存在未确认 MINOR 偏差，需质控确认后才可放行；
 * openMinorExcursionKeys 给出待确认 MINOR 偏差标识。
 */
public record ReleaseBlockResponse(
        String batchKey,
        boolean releaseBlocked,
        List<String> openMajorExcursionKeys,
        boolean minorQualityConfirmationPending,
        List<String> openMinorExcursionKeys
) {
}
