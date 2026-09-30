package com.example.starter.firmware.api;

import com.example.starter.firmware.repo.TaskProfileSnapshotRepository.SnapshotRow;

import java.util.List;

/**
 * 任务创建时冻结的画像快照视图，供失败率审计核对筛选所用画像版本与内容。
 */
public record TaskProfileSnapshotView(long taskId, int profileVersion, String model, Integer memoryMb,
                                      List<String> capabilityTags) {

    public static TaskProfileSnapshotView of(SnapshotRow row, List<String> tags) {
        return new TaskProfileSnapshotView(row.taskId(), row.profileVersion(), row.model(),
                row.memoryMb(), tags);
    }
}
