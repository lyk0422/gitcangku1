package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 提交证据包版本请求：一次可更新多个检查项结果并追加材料批次摘要；
 * 服务端按完整最终包校验，任一检查项失败则整次回滚，不产生半成品版本。
 *
 * @param requestId       全局唯一请求标识（幂等键）
 * @param expectedVersion 设备期望版本号，与当前版本不一致时返回 409
 * @param submittedBy     提交人（维护人员标识），该人不可作为本周期完工确认人
 * @param checkResults    本次更新的检查项结果（可空或为空列表表示仅追加材料）；每项 result 为 PASS/FAIL
 * @param materials       本次追加的材料批次摘要（可空或为空列表表示仅更新检查项）
 */
public record SubmitEvidenceRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String submittedBy,
        @Valid List<CheckResultInput> checkResults,
        @Valid List<MaterialSummaryInput> materials) {

    /**
     * 检查项结果输入。
     *
     * @param itemCode 检查项编码，须属于设备当前周期固化的模板版本
     * @param result   检查结果，仅接受 PASS 或 FAIL
     * @param remark   备注（可空，null 表示无备注）
     */
    public record CheckResultInput(
            @NotBlank String itemCode,
            @NotBlank String result,
            @Size(max = 256) String remark) {
    }

    /**
     * 材料批次摘要输入。
     *
     * @param batchCode 材料批次编码，须存在且未撤销，同一设备周期内不可重复
     * @param quantity  使用数量（正整数）
     * @param unit      数量单位（非空）
     */
    public record MaterialSummaryInput(
            @NotBlank String batchCode,
            @NotNull @Positive Long quantity,
            @NotBlank @Size(max = 16) String unit) {
    }
}
