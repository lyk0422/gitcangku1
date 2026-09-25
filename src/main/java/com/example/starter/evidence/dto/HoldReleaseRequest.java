package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量解除冻结请求。先校验请求方（须为各冻结创建人）与冻结版本，
 * 任一失败整批回滚，不产生部分解除。
 *
 * @param commandKey 幂等命令键
 * @param releases   解除项列表（holdKey + 期望版本）
 */
public record HoldReleaseRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotEmpty List<@Valid HoldReleaseItem> releases) {

    /**
     * 单个解除项。
     *
     * @param holdKey 冻结业务键
     * @param version 期望的冻结版本（须与当前版本一致）
     */
    public record HoldReleaseItem(
            @NotBlank @Size(max = 64) String holdKey,
            @NotNull Integer version) {
    }
}
