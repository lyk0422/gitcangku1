package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 批量提交更正附页请求：先校验所有原观测、采集者和最终坐标边界，任一失败整批不产生附页。
 *
 * @param corrKey 整批幂等键：指纹含全部条目的规范化参数；同键同参重放，失败不占键
 * @param items   附页条目列表（1~10 条，观测记录不可重复）
 */
public record BatchCorrigendumRequest(
        @NotBlank @Size(max = 128) String corrKey,
        @NotNull @Size(min = 1, max = 10) List<@Valid Item> items) {

    /**
     * 批量附页条目：字段含义与单条提交一致，observationId 在路径之外显式指定。
     *
     * @param observationId 观测记录唯一标识
     * @param baseVersion   原观测版本号，须等于提交时观测当前版本
     * @param diffs         字段差异：键仅允许 location/reading/note，值为更正值
     * @param reason        更正原因
     * @param collector     采集者标识
     */
    public record Item(
            @NotBlank @Size(max = 64) String observationId,
            @NotNull @Min(1) Integer baseVersion,
            @NotNull Map<String, String> diffs,
            @NotBlank @Size(max = 1024) String reason,
            @NotBlank @Size(max = 128) String collector) {
    }
}
