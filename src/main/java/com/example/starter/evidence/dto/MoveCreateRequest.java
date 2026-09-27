package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 位置迁移申请请求。包含证物集合、源库位、目标库位与 expectedVersion。
 * 证物集合会去重并按字典序规范化，集合换序视为同参（moveKey 指纹相同）。
 *
 * @param commandKey         幂等命令键
 * @param moveKey            位置迁移业务键，全局唯一
 * @param evidenceKeys       证物业务键集合，非空；重复键与换序不影响参数指纹
 * @param sourceLocationCode 源库位编码；确认时每件证物当前库位必须等于该值
 * @param targetLocationCode 目标库位编码，不能与源库位相同；确认时必须为启用状态
 * @param expectedVersion    申请时每件证物的版本；确认时任一证物版本不符则整单失败
 */
public record MoveCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String moveKey,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys,
        @NotBlank @Size(max = 64) String sourceLocationCode,
        @NotBlank @Size(max = 64) String targetLocationCode,
        @NotNull Long expectedVersion) {
}
