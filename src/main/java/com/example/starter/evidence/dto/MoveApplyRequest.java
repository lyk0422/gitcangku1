package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 库位迁移申请（首人确认）请求。含证物集合、源库位、目标库位与 expectedVersion；
 * 证物集合换序视为同参（服务端规范化为升序去重）。
 *
 * @param commandKey   幂等命令键，同时作为迁移业务键 moveKey
 * @param evidenceKeys 证物业务键集合，非空；服务端按升序去重规范化
 * @param sourceLocationCode 源库位编码
 * @param targetLocationCode 目标库位编码
 * @param expectedVersion    期望的每件证物当前库位版本号
 */
public record MoveApplyRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotEmpty List<@Size(max = 64) String> evidenceKeys,
        @NotBlank @Size(max = 64) String sourceLocationCode,
        @NotBlank @Size(max = 64) String targetLocationCode,
        @NotNull Long expectedVersion) {
}
