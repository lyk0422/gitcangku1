package com.example.starter.evidence;

/**
 * 命令执行结果：HTTP 状态码 + 响应体 JSON 字符串。
 * 首次执行与幂等重放均通过该形式返回，保证重放响应与首次完全一致。
 *
 * @param status HTTP 状态码
 * @param body   响应体 JSON
 */
public record StoredResponse(int status, String body) {
}
