package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 返工重投请求。对已完成全部必做检验但判定不合格（REJECTED）、且尚未放行的批次，
 * 提交 reworkKey 与返工说明生成一个新的返工批次。
 * reworkKey 为返工业务幂等键：同键同参重放返回首次返工批次，同键改参返回 409；
 * reworkBatchKey 为新返工批次全局唯一业务键；reworkBatchNo 为新批次批号。
 */
public record ReworkRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reworkKey 不能为空") String reworkKey,
        @NotBlank(message = "reworkBatchKey 不能为空") String reworkBatchKey,
        @NotBlank(message = "reworkBatchNo 不能为空") String reworkBatchNo,
        @NotBlank(message = "reason 返工说明不能为空") String reason
) {
}
