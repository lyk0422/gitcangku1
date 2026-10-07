package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * peer 依赖声明请求：为已存在的制品版本登记 1～3 条 peer，登记后不可改。
 *
 * @param operator 操作者，非空；服务端校验并参与幂等参数比较
 * @param peers    peer 声明列表，名称不得与制品自身同名，区间为正整数闭区间
 */
public record DeclarePeersRequest(
        @NotBlank String operator,
        @Valid @NotNull @Size(min = 1, max = 3) List<@Valid DependencySpec> peers) {

    public DeclarePeersRequest {
        if (peers != null) {
            peers = List.copyOf(peers);
        }
    }
}
