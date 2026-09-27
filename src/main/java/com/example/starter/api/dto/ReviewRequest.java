package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交审查请求。明确指定航线版本与空域版本，任一不是当前版本返回 409。
 *
 * @param routeId         航线唯一标识
 * @param routeVersion    明确的航线版本
 * @param airspaceVersion 明确的空域版本
 * @param emergency       是否为应急航线审查：true 时不因命中应急走廊被拒（走廊段只允许应急使用），
 *                        但仍必须通过既有禁飞区审查，不能以走廊绕过禁区；false/缺省为常规审查
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record ReviewRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        boolean emergency,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容旧调用：缺省按常规审查（emergency=false）。 */
    public ReviewRequest(String routeId, Integer routeVersion, Long airspaceVersion,
                         String requestId) {
        this(routeId, routeVersion, airspaceVersion, false, requestId);
    }
}
