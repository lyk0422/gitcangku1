package com.example.starter.handoff.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 目标厂接收请求。必须提交完整 manifest（冻结清单的全部批次业务键，允许换序）、
 * 逐批封签号与目标厂接收人；服务端重新读取清单、版本及完整血缘闭包，
 * 拒绝遗漏、多余、重复、封签不符、运输中新增召回或任何非 IN_TRANSIT 状态。
 * requestId 为幂等键；失败保持全部批次在途且不生成部分接收链。
 */
public record ReceiveHandoffRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "receiver 不能为空") String receiver,
        @NotNull(message = "manifest 不能为空")
        @Size(min = 1, max = 50, message = "manifest 必须包含 1～50 个批次")
        List<@NotBlank(message = "manifest 批次不能为空") String> manifest,
        @NotNull(message = "seals 不能为空")
        @Size(min = 1, max = 50, message = "seals 必须包含 1～50 个封签")
        List<@Valid SealSpec> seals
) {

    /**
     * 逐批封签：batchKey 对应清单项，sealNo 必须与发运时写入的封签一致。
     */
    public record SealSpec(
            @NotBlank(message = "封签批次 batchKey 不能为空") String batchKey,
            @NotBlank(message = "sealNo 不能为空") String sealNo
    ) {
    }
}
