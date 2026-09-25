package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发布门禁请求：单图或批量锁定图统一为 ID 列表，附规范化目标地区集合。
 *
 * @param lockFileIds 待发布锁定图 ID 列表（1～20 张）
 * @param regions     发布目标地区代码集合，服务端规范化（大写、去重、升序）
 */
public record ReleaseRequest(
        @NotEmpty @Size(max = 20) List<@jakarta.validation.constraints.Positive Long> lockFileIds,
        @NotEmpty @Size(max = 32) List<@NotBlank String> regions) {

    public ReleaseRequest {
        lockFileIds = List.copyOf(lockFileIds);
        regions = List.copyOf(regions);
    }
}
