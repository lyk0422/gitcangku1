package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设备标签批量修改请求。同一批次的全部设备先校验完整最终状态，
 * 再在单个事务中提交；任一设备失败则整批回滚，不产生半成品。
 */
public record TagBatchUpdateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotEmpty @Valid List<TagUpdateItem> updates) {

    /**
     * 单台设备的标签增删。add 与 remove 均可选，但至少给出一个非空集合；
     * 最终标签集合 = (当前 ∪ add) \ remove。
     */
    public record TagUpdateItem(
            @NotBlank @Size(max = 64) String deviceId,
            List<String> add,
            List<String> remove) {
    }
}
