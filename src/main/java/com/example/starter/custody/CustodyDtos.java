package com.example.starter.custody;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 接口入参与出参。重量单位均为公斤(整数)。
 */
public final class CustodyDtos {

    private CustodyDtos() {
    }

    /** 来源登记请求:holder为入库持有人。 */
    public record SourceRequest(
            @NotBlank String voucherNo,
            @NotBlank String species,
            @Positive int weightKg,
            @NotBlank String batchCode,
            @NotBlank String holder) {
    }

    /** 批次视图。 */
    public record BatchView(String batchCode, String voucherNo, String species,
                            int weightKg, String holder, String status) {
        public static BatchView of(BatchRecord b) {
            return new BatchView(b.batchCode(), b.voucherNo(), b.species(),
                    b.weightKg(), b.holder(), b.status().name());
        }
    }

    /** 拆分请求:一次提交全部子批次,重量之和必须等于父批次当前可拆重量。 */
    public record SplitRequest(
            @NotBlank String requestId,
            @NotEmpty List<ChildSpec> children) {
    }

    /** 子批次规格:voucherNo可空,非空时必须与父批次凭证一致(禁止跨来源合并)。 */
    public record ChildSpec(
            @NotBlank String batchCode,
            @Positive int weightKg,
            String voucherNo) {
    }

    /** 拆分响应。 */
    public record SplitResponse(String parentCode, List<BatchView> children) {
    }

    /** 移交申请请求:fromHolder必须为当前持有人。 */
    public record TransferRequest(
            @NotBlank String fromHolder,
            @NotBlank String toHolder) {
    }

    /** 移交视图。 */
    public record TransferView(long id, String batchCode, String fromHolder, String toHolder,
                               String status, String rejectReason) {
    }

    /** 签收请求:decision为ACCEPT或REJECT,REJECT必须给rejectReason。 */
    public record SignoffRequest(
            @NotBlank String requestId,
            @NotBlank String decision,
            String rejectReason) {
    }

    /** 追溯响应:chain按来源到当前批次排列。 */
    public record TraceResponse(String batchCode, int weightKg, String currentHolder,
                                List<TraceNode> chain) {
    }

    /** 追溯链节点。 */
    public record TraceNode(String batchCode, String voucherNo, String species,
                            int weightKg, String status) {
    }
}