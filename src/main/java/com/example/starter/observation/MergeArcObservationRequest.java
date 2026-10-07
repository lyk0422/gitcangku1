package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 圆弧观测离线提交（三方合并）请求：携带基线版本与圆弧集合完整候选值。
 * 圆弧集合整体是一个完整字段组，不按单条圆弧或单个数值拆分合并。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param baseVersion 离线修改所基于的基线版本号
 * @param operator    操作者标识（非空）
 * @param arcs        圆弧集合候选值，1 至 16 条，集合顺序不影响比较
 */
public record MergeArcObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer baseVersion,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 1, max = 16) List<ArcComponent> arcs) {
}
