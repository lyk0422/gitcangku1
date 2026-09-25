package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交审核请求。明确指定航线版本与空域版本，任一不是当前版本返回 409。
 *
 * <p>可声明备降优先级（缺省 NORMAL）；EMERGENCY 必须附事件编号。
 * 可携带时空段（cellX/cellY/windowStart/windowEnd 四个字段必须同时出现或同时缺省），
 * windowStart/windowEnd 为 epoch 毫秒（UTC），规范化时向下取整到分钟；
 * 声明时空段后审核将占用对应容量桶容量（未建桶则不限容量）。</p>
 *
 * @param routeId         航线唯一标识
 * @param routeVersion    明确的航线版本
 * @param airspaceVersion 明确的空域版本
 * @param priority        备降优先级：NORMAL / EMERGENCY；null 视为 NORMAL
 * @param eventNo         事件编号；EMERGENCY 必填，NORMAL 忽略
 * @param cellX           空间单元 X 索引
 * @param cellY           空间单元 Y 索引
 * @param windowStart     时间窗起始，epoch 毫秒（UTC），规范化到分钟
 * @param windowEnd       时间窗结束，epoch 毫秒（UTC），规范化后须大于起始
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record ReviewRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        @Size(max = 16) String priority,
        @Size(max = 64) String eventNo,
        Integer cellX,
        Integer cellY,
        Long windowStart,
        Long windowEnd,
        @NotBlank @Size(max = 64) String requestId) {

    /**
     * 兼容构造：不声明优先级与时空段的审核（等价 NORMAL、不限容量）。
     */
    public ReviewRequest(String routeId, Integer routeVersion, Long airspaceVersion, String requestId) {
        this(routeId, routeVersion, airspaceVersion, null, null, null, null, null, null, requestId);
    }
}
