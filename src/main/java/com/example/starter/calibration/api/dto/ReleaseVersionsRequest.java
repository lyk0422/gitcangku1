package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 版本化批量放行请求。每批 1～50 项，测量键互不重复，整批原子生效。
 *
 * @param items 放行项（测量键 + 显式修订号）
 */
public record ReleaseVersionsRequest(List<Item> items) {

    /**
     * 单个版本化放行项。
     *
     * @param measurementKey 业务测量键
     * @param revision       修订号；必须正是该键当前最新版本
     */
    public record Item(String measurementKey, Integer revision) {
    }
}
