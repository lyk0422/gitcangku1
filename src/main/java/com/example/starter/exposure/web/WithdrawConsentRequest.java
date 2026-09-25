package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤回同意请求体，仅携带幂等键；同意区间编号来自路径。
 * 撤回仅将指定同意区间在撤回时刻截断（终点改为撤回时刻），
 * 只影响之后的预占；撤回前已建立的预占仍按固化快照结算。
 *
 * @param requestId 写操作全局唯一幂等键
 */
public record WithdrawConsentRequest(
        @NotBlank @Size(max = 64) String requestId
) {
}
