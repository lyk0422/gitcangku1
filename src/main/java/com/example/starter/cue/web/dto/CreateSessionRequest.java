package com.example.starter.cue.web.dto;

/**
 * 新建排练场次请求。versionId 为空时绑定剧目最新冻结版本。
 *
 * @param production 剧目名称
 * @param versionId  指定绑定的冻结版本 ID，可为空
 */
public record CreateSessionRequest(String production, Long versionId) {
}