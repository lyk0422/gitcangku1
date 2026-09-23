package com.example.starter.evidence.aliquot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 审核前取消联合取样申请请求：仅申请保管人可在两名审核人确认完成前取消，一次释放全部预留。
 *
 * @param commandKey 幂等命令键
 * @param note       取消备注，可空
 */
public record SamplingCancelRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @Size(max = 512) String note) {
}
