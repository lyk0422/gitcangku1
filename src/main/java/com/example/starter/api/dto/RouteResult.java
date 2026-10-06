package com.example.starter.api.dto;

/**
 * 航线创建/替换结果。
 *
 * @param routeId        航线标识
 * @param version        操作后的航线版本
 * @param envelopeRadius 操作后生效的水平包络半径（米）
 */
public record RouteResult(String routeId, int version, int envelopeRadius) {
}
