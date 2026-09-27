package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 跨对象查询请求：一次查询指定允许驻留区域集合与对象键集合。
 * 仅当授权有效、epoch 一致且所有对象驻留区域均在集合内时返回数据；任一不满足整次 403 或 410，不返回部分结果。
 *
 * @param requestId  查询幂等请求标识，同参重放返回首次完整快照，异参 409
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH / PERSONALIZATION
 * @param regions    允许的驻留区域代码集合（至少一个）
 * @param recordKeys 待查询对象记录键集合（至少一个，去重后按字典序固化）
 */
public record QueryRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotEmpty(message = "允许区域集合不能为空") List<@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*") String> regions,
        @NotEmpty(message = "查询对象集合不能为空") List<@NotBlank @Size(max = 128) String> recordKeys) {
}
