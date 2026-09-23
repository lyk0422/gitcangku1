package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 组合借出包创建请求。经办人一次选择 2~20 件当前可借证物；
 * 全部证物须属同一案件且当前保管点一致；任一件不可借或版本变化则整包失败。
 *
 * @param requestId 幂等请求键（子集换序视为同参）
 * @param packageKey 组合包业务键，全局唯一
 * @param borrowerId 统一借用人
 * @param purpose    统一借出用途，非空
 * @param dueAt      统一 UTC 应还时刻
 * @param items      包内证物及各自期望版本（2~20 件，不允许重复）
 */
public record PackageCreateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String packageKey,
        @NotBlank @Size(max = 64) String borrowerId,
        @NotBlank @Size(max = 512) String purpose,
        @NotNull LocalDateTime dueAt,
        @NotNull @Size(min = 2, max = 20) List<@Valid Item> items) {

    /**
     * 包内单件证物期望版本。
     *
     * @param evidenceKey     证物业务键
     * @param expectedVersion 客户端持有的证物版本，须与服务端当前版本一致
     */
    public record Item(
            @NotBlank @Size(max = 64) String evidenceKey,
            long expectedVersion) {
    }

    /**
     * 规范化副本：items 按 evidenceKey 排序，保证子集换序的请求指纹一致。
     */
    public PackageCreateRequest canonical() {
        List<Item> sorted = items.stream()
                .sorted(Comparator.comparing(Item::evidenceKey))
                .toList();
        return new PackageCreateRequest(requestId, packageKey, borrowerId, purpose, dueAt, sorted);
    }
}
