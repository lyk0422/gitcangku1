package com.example.starter.consent.migration.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用途拆分迁移激活请求：必须原样回传预览规格，并提交预览中全部有效授权与数据记录
 * 的 expectedVersion 及映射结果。任一遗漏、多余、属性变化、范围越界或出现多个目标，整单失败。
 *
 * @param requestId    幂等请求标识；同参重放首次快照，异参 409，失败不占键
 * @param preview      预览规格（目录版本、旧用途、迁移键、生效窗口、新用途映射），集合换序等价
 * @param activeGrants 预览中全部有效授权的版本与拆分目标用途
 * @param records      预览中全部数据记录的版本与映射结果（已撤回隔离数据须保留旧用途）
 */
public record MigrationActivateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Valid MigrationPreviewRequest preview,
        @NotNull @Valid List<ActiveGrantSelection> activeGrants,
        @NotNull @Valid List<RecordSelection> records) {

    /**
     * 有效授权的激活确认项。
     *
     * @param subjectKey      主体标识
     * @param epoch           授权代次
     * @param expectedVersion 预览返回的授权版本
     * @param targetPurposes  授权原范围拆分到的全部目标用途代码
     */
    public record ActiveGrantSelection(String subjectKey,
                                       @NotNull Integer epoch,
                                       @NotNull Integer expectedVersion,
                                       @NotNull List<@NotBlank String> targetPurposes) {
    }

    /**
     * 数据记录的激活确认项。
     *
     * @param subjectKey      主体标识
     * @param epoch           所属授权代次
     * @param recordKey       记录键
     * @param expectedVersion 预览返回的记录版本
     * @param targetPurpose   映射结果：新用途代码 / UNMAPPED；已撤回隔离数据必须为旧用途代码
     */
    public record RecordSelection(String subjectKey,
                                  @NotNull Integer epoch,
                                  @NotBlank String recordKey,
                                  @NotNull Integer expectedVersion,
                                  @NotBlank String targetPurpose) {
    }
}
