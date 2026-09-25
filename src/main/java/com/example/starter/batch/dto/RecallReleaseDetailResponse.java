package com.example.starter.batch.dto;

/**
 * 召回解除申请详情：申请本体 + 批准后的不可变评审快照（未批准时 snapshot 为 null）。
 */
public record RecallReleaseDetailResponse(
        RecallReleaseResponse release,
        RecallReleaseSnapshotResponse snapshot
) {
}
