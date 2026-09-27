package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量原子改签请求：所有改签项先在完整后态上统一校验（状态、版本、关联、时隙冲突、
 * 危险品隔离，以及新计划之间的时隙互查），全部合法才在一个事务内提交；
 * 任一项非法整次请求不改变任何时隙或编组。requestKey 为整批请求的幂等键。
 */
public record BatchRescheduleRequest(
        @NotBlank String requestKey,
        @NotBlank @Size(max = 64) String operator,
        @NotEmpty @Size(max = 50) List<@Valid BatchRescheduleItemRequest> items) {
}
