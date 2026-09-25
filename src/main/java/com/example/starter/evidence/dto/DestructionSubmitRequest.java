package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提交销毁申请请求。服务端先校验最终证物状态、封签与全部有效冻结，
 * 任一证物命中有效冻结即返回 422 且不生成任何申请行。
 *
 * @param commandKey   幂等命令键
 * @param requestKey   销毁申请业务键，全局唯一
 * @param evidenceKeys 申请销毁的证物集合（非空，服务端规范化排序去重）
 */
public record DestructionSubmitRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String requestKey,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
