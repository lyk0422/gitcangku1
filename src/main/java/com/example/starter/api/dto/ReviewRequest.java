package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交审核请求。明确指定航线版本与空域版本，任一不是当前版本返回 409。
 * 提交气象版本时还要求其等于当前气象（值班）版本，并对路径经过区域做
 * 预测气象与最低标准比对；不提交（null）则退化为纯禁飞区审查。
 *
 * @param routeId         航线唯一标识
 * @param routeVersion    明确的航线版本
 * @param airspaceVersion 明确的空域版本
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 * @param weatherVersion  明确的预测气象版本；null 表示不做气象评估
 */
public record ReviewRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        @NotBlank @Size(max = 64) String requestId,
        Long weatherVersion) {

    /**
     * 兼容构造：不提交气象版本的纯禁飞区审查。
     */
    public ReviewRequest(String routeId, Integer routeVersion, Long airspaceVersion, String requestId) {
        this(routeId, routeVersion, airspaceVersion, requestId, null);
    }
}