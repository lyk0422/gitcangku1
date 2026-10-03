package com.example.starter.timber.web;

import java.util.List;

import com.example.starter.timber.SplitChildInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

/**
 * 监管链REST接口的请求与响应DTO。
 */
public final class TimberDtos {

    private TimberDtos() {
    }

    /** 来源登记请求：凭证编号、树种、入库重量（公斤）、初始持有人。 */
    public record RegisterSourceRequest(@NotBlank String voucherNo, @NotBlank String species,
                                        @Positive int weightKg, @NotBlank String holder) {
    }

    /** 来源登记响应：含系统生成的根批次编号。 */
    public record SourceResponse(String voucherNo, String species, int weightKg, String holder, String rootBatchNo) {
    }

    /** 拆分请求：一次提交全部子批次，requestId为幂等键。 */
    public record SplitRequest(@NotBlank String requestId,
                               @NotEmpty List<@Valid SplitChildInput> children) {
    }

    /** 批次视图。 */
    public record BatchView(String batchNo, int weightKg, String holder, String voucherNo,
                            String species, boolean leaf) {
    }

    /** 拆分响应：父批次编号与新建子批次列表。 */
    public record SplitResponse(String parentBatchNo, List<BatchView> children) {
    }

    /** 移交申请请求：fromHolder必须等于当前持有人，requestId为幂等键。 */
    public record TransferRequest(@NotBlank String requestId, @NotBlank String fromHolder,
                                  @NotBlank String toHolder) {
    }

    /** 移交申请响应。 */
    public record TransferResponse(long transferId, String batchNo, String fromHolder,
                                   String toHolder, String status) {
    }

    /** 签收请求：decision为ACCEPT或REJECT，REJECT时reason必填，requestId为幂等键。 */
    public record SignRequest(@NotBlank String requestId, @NotBlank String decision, String reason) {
    }

    /** 签收响应：status为ACCEPTED或REJECTED，holder为签收后的当前持有人。 */
    public record SignResponse(long transferId, String batchNo, String status, String holder,
                               String rejectReason) {
    }

    /** 追溯链节点，按从来源根到当前批次的顺序排列。 */
    public record TraceNode(String batchNo, int weightKg, String holder, boolean leaf) {
    }

    /** 追溯响应：当前批次信息加完整祖先链（含自身，根在前）。 */
    public record TraceResponse(String batchNo, String voucherNo, String species, int weightKg,
                                String holder, List<TraceNode> chain) {
    }

    /** 统一错误响应。 */
    public record ErrorResponse(String code, String message) {
    }
}