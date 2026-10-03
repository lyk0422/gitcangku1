package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 授粉结果确认请求，须在操作执行后 72 小时内完成。
 *
 * @param requestId 幂等请求键：同键同参重放返回原结果，同键异参返回 409
 * @param result 确认结果：SUCCESS=成功（固化花粉批号），FAILED=失败（释放花序）
 */
public record ConfirmOperationRequest(
        @NotBlank String requestId,
        @NotNull ConfirmResult result) {

    /**
     * 确认结果枚举。
     */
    public enum ConfirmResult {
        /** 授粉成功。 */
        SUCCESS,
        /** 授粉失败。 */
        FAILED
    }
}