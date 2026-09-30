package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布人批量投放筛选结果：created 为当次通过兼容门禁并创建（或已存在）的任务，
 * excluded 为不兼容设备及原因。不兼容设备不创建任务，也不计入失败率。
 */
public record DispatchResponse(long releaseId, List<TaskView> created, List<ExclusionView> excluded) {
}
