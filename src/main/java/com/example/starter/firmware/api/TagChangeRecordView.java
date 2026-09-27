package com.example.starter.firmware.api;

import java.util.List;

/**
 * 单条标签批量变更历史视图。changes 为创建时的规范化快照，后续不改写。
 */
public record TagChangeRecordView(long id, long dictVersion, String operator, int deviceCount,
                                  List<ChangeItem> changes, String changedAtUtc) {

    /**
     * 单台设备的规范化变更：add/remove 均为规范化升序标签。
     */
    public record ChangeItem(String deviceId, List<String> add, List<String> remove) {
    }
}
