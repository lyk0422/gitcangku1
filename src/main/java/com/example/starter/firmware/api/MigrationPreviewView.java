package com.example.starter.firmware.api;

import java.util.List;

/**
 * 迁移预览视图：完整后态的队列规模与配额，不写数据。
 */
public record MigrationPreviewView(long campaignId, List<PreviewItemView> items,
                                   List<CohortPostStateView> cohorts) {
}
