package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建更正附录请求：观测记录员对已解决观测簇提交字段差异，以前一附录版本为乐观并发依据。
 *
 * @param requestId                全局唯一请求标识（幂等去重键）
 * @param expectedPreviousVersion 客户端依据的前一附录版本号；首份附录传 0，与最新附录版本不一致返回 409
 * @param changes                  字段差异列表，至少一项；字段不允许重复，新值不允许为空（禁止删除字段）
 * @param operator                 提交附录的观测记录员标识
 */
public record CreateCorrigendumRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(0) Integer expectedPreviousVersion,
        @NotEmpty @Valid List<FieldChangeRequest> changes,
        @NotBlank @Size(max = 128) String operator) {
}
