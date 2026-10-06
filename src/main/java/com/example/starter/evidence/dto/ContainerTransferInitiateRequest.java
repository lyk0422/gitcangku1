package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 根箱交接发起请求。仅当前保管人可发起，接收人必须与发起人不同；
 * 发起后整树（箱、封条、版本）冻结。
 *
 * @param requestId   全局幂等请求键
 * @param toCustodian 指定接收人
 */
public record ContainerTransferInitiateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String toCustodian) {
}
