package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划冻结牵引快照响应：发布/改签成功时刻的牵引方式与每段档案值、版本，后续变更不改历史。
 */
public record TractionSnapshotResponse(String scheduleKey, List<TractionSnapshotItem> snapshots) {
}