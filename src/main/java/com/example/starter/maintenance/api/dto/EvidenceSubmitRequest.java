package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 证据包提交请求：一次提交一组检查项结果和至少一个材料批次摘要，生成周期内递增的新版本。
 * 先校验完整最终包（归属模板、恰一个结果、材料合法且周期内不重复），任一失败整次回滚。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param submittedBy      证据提交人（维护人员）；提交人本人不能确认完工
 * @param checks           检查项结果列表；每个当前模板必检项最终必须恰有一个 PASS 结果
 * @param materials        材料批次摘要列表；同设备同周期批次不可重复，引用时批次必须有效
 */
public record EvidenceSubmitRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String submittedBy,
        @NotEmpty @Valid List<CheckResultInput> checks,
        @NotEmpty @Valid List<MaterialSummaryInput> materials) {

    /**
     * 检查项结果输入。
     *
     * @param itemCode  检查项编码，必须属于设备当前保养模板版本；同次提交不可重复
     * @param result    检查结果，取值 PASS 或 FAIL
     * @param note      检查备注，可空，null 表示无备注
     */
    public record CheckResultInput(
            @NotBlank String itemCode,
            @NotBlank String result,
            @Size(max = 512) String note) {
    }

    /**
     * 材料批次摘要输入。单位以批次登记为准，不由调用方提供。
     *
     * @param materialId  材料批次号，引用时批次必须为 ACTIVE
     * @param quantity    实际用量，正数，精度到千分位（单位与批次登记一致）
     */
    public record MaterialSummaryInput(
            @NotBlank String materialId,
            @NotNull @Positive BigDecimal quantity) {
    }
}
