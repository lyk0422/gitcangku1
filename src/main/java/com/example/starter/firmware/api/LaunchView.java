package com.example.starter.firmware.api;

import java.util.List;

/**
 * 画像投放单视图（发布人视角）：所选版本、兼容设备任务（含冻结画像版本）、
 * 排除设备及原因、失败率统计。创建响应与查询响应同构，同 requestId 重放首次目标清单。
 */
public record LaunchView(long launchId, long versionId, String versionName,
                         List<LaunchTaskView> tasks, List<LaunchExclusionView> exclusions,
                         LaunchStatsView stats) {
}
