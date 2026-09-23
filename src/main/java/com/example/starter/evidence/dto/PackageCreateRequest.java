package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 组合借出请求：经办人一次选择 2~20 件当前可借证物。
 * 全部证物必须属于同一案件、当前保管点一致；任一件已借出、封条异常或版本变化，整包创建失败，
 * 不形成任何部分借出记录。
 *
 * @param commandKey 幂等命令键
 * @param packageKey 组合包业务键，全局唯一
 * @param purpose    统一借出用途，非空
 * @param borrowerId 统一借用人，必须与经办人（当前保管人）不同
 * @param dueAt      统一 UTC 到期时刻，须晚于服务端当前时刻且不超过 72 小时
 * @param items      2~20 件证物及其借出时提交的 expectedVersion
 */
public record PackageCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String packageKey,
        @NotBlank @Size(max = 512) String purpose,
        @NotBlank @Size(max = 64) String borrowerId,
        @NotNull LocalDateTime dueAt,
        @NotEmpty @Size(min = 2, max = 20) @Valid List<PackageItemRequest> items) {

    /**
     * 组合借出单件请求。
     *
     * @param evidenceKey    证物业务键
     * @param expectedVersion 经办人提交的期望封条/状态版本；与服务端当前版本不一致则整包失败
     */
    public record PackageItemRequest(
            @NotBlank @Size(max = 64) String evidenceKey,
            @NotNull Long expectedVersion) {
    }
}
