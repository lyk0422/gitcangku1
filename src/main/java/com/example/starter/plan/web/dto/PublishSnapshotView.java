package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 一份不可变发布快照视图，按快照 id 升序返回（即发布提交顺序）。
 * publishedBy 为 null 表示发布请求未登记操作者。
 */
public record PublishSnapshotView(long snapshotId, String scheduleKey, String publishedBy,
                                  Instant publishedAt, List<SnapshotDwellView> dwells) {
}
