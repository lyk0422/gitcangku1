package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 观测批量提交请求：整批一次事务提交，逐条按提交时刻匹配维护窗口并固化标记；
 * 任一观测格式或站点引用失败整批回滚。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param submissions 本批观测提交明细，可混合多个站点；逐条匹配窗口
 */
public record SubmitObservationBatchRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotEmpty @Valid List<Item> submissions) {

    /**
     * 单条观测提交明细。
     *
     * @param submissionId  提交唯一标识（全局唯一，重复由数据库与业务判定 409）
     * @param stationId     所属观测站标识（必须已登记）
     * @param observationId 归属观测记录标识（冲突簇归并键）
     * @param submittedAtUtc 观测提交时刻（UTC）；窗口按左闭右开匹配该时刻
     * @param location      观测地点原始值
     * @param reading       观测读数原始值，十进制字符串，最多三位小数
     * @param note          观测备注原始值
     */
    public record Item(
            @NotBlank @Size(max = 128) String submissionId,
            @NotBlank @Size(max = 64) String stationId,
            @NotBlank @Size(max = 64) String observationId,
            @NotNull Instant submittedAtUtc,
            @NotNull @Size(max = 512) String location,
            @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
            String reading,
            @NotNull @Size(max = 1024) String note) {
    }
}
