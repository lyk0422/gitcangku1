package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 代理批量查询请求：代理对多个主体发起单用途批量查询。
 * 每个主体的当前授权代次均须有该代理有效委托且覆盖请求用途，
 * 任一缺失、过期或已撤销则整批 403 且不返回任何数据。
 *
 * @param requestId   幂等请求标识，成功快照以该标识固化
 * @param delegateId  代理人标识
 * @param purpose     查询用途
 * @param subjectKeys 主体标识列表，至少一个
 */
public record BatchQueryRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String delegateId,
        @NotNull Purpose purpose,
        @NotEmpty List<@NotBlank @Size(max = 128) String> subjectKeys) {
}
