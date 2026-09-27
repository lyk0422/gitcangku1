package com.example.starter.maintenance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 删除维护窗口请求：仅未被合并快照封存的生效窗口可删，封存窗口拒绝删除（只能追加更正）。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 */
public record DeleteWindowRequest(
        @NotBlank @Size(max = 128) String requestId) {
}
