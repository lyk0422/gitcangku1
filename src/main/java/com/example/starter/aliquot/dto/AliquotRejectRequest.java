package com.example.starter.aliquot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取样审核拒绝请求。任一审核人均可拒绝；拒绝后一次释放全部预留，单据终结。
 *
 * @param commandKey 幂等命令键
 */
public record AliquotRejectRequest(
        @NotBlank @Size(max = 64) String commandKey) {
}
