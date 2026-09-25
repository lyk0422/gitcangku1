package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 销毁申请提交请求。requestKey 兼作幂等命令键。
 * 提交时校验全部证物的最终状态、封签与所有有效冻结；任一证物命中冻结返回 422
 * 并稳定列出 holdKey，不生成部分销毁申请。
 * evidenceKeys 在构造时规范化（去重、字典序排序）。
 *
 * @param requestKey   销毁申请业务键（兼幂等命令键），全局唯一
 * @param evidenceKeys 证物键集合（提交后规范化排序）
 * @param reason       申请原因
 */
public record DestructionSubmitRequest(
        @NotBlank @Size(max = 64) String requestKey,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys,
        @NotBlank @Size(max = 512) String reason) {

    public DestructionSubmitRequest {
        if (evidenceKeys != null) {
            evidenceKeys = evidenceKeys.stream().distinct().sorted().toList();
        }
    }
}
