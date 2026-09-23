package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 重复观测簇候选预览请求：提交完整的候选记录键集合，不做后台自动聚类。
 *
 * @param recordKeys 候选记录键集合，数量必须为 2-20
 */
public record ClusterPreviewRequest(
        @NotNull @Size(min = 2, max = 20) List<@NotBlank @Size(max = 64) String> recordKeys) {
}
