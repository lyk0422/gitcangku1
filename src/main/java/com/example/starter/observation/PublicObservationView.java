package com.example.starter.observation;

/**
 * 公开查询视图的密封接口：正常内容（{@link ObservationResponse}）或禁运标记（{@link EmbargoedResponse}）。
 * 仅用于公开查询端点的响应类型区分，不影响已存储的幂等响应体格式。
 */
public sealed interface PublicObservationView permits ObservationResponse, EmbargoedResponse {
}
