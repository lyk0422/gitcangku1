package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 代理批量查询请求：代理人对多个主体发起批量查询，
 * 每个主体的当前授权代次均须有该代理有效委托且覆盖请求用途。
 *
 * @param requestId 幂等请求标识，成功批次生成快照
 * @param agentKey  代理人标识（合成字符串）
 * @param subjects  主体标识列表，规范化去重后按标识排序
 * @param purposes  请求用途集合，规范化去重后按用途名排序
 */
public record DelegateQueryRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String agentKey,
        @NotEmpty List<@NotBlank @Size(max = 128) String> subjects,
        @NotEmpty List<Purpose> purposes) {
}
