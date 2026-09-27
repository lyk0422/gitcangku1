package com.example.starter.maintenance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤回维护窗口请求：仅未被封存快照覆盖的窗口可撤回；已封存窗口只能追加更正窗口。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 */
public record WithdrawWindowRequest(
        @NotBlank @Size(max = 128) String requestId) {
}
