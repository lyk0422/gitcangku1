package com.example.starter.firmware.api;

import java.util.List;

/**
 * 拓扑波次视图：领取成功即冻结成员集合与任务，重放返回同一快照。
 */
public record WaveView(long waveId, long releaseId, int waveNo, List<String> members,
                       List<TaskView> tasks) {
}
