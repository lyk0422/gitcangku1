package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 库位迁移申请。moveKey 为整张迁移的幂等键：同键同参（证物集合换序视为同参）
 * 重放完整迁移结果，同键改参返回 409，申请校验失败不占键。
 *
 * @param moveKey         迁移业务键，全局唯一
 * @param evidenceKeys    待迁移证物业务键集合（去重升序后规范化）
 * @param sourceLocation  源库位业务键，全部证物当前库位须等于该库位
 * @param targetLocation  目标库位业务键，确认执行时须仍为 ACTIVE
 * @param expectedVersion 源库位库存版本（取自库位库存查询），第二人确认时仍须相等
 */
public record MoveApplyRequest(
        @NotBlank @Size(max = 64) String moveKey,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys,
        @NotBlank @Size(max = 64) String sourceLocation,
        @NotBlank @Size(max = 64) String targetLocation,
        @PositiveOrZero int expectedVersion) {
}
