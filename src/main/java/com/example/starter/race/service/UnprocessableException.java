package com.example.starter.race.service;

/**
 * 语义校验失败（HTTP 422）：请求格式合法但与既有业务数据矛盾，
 * 例如分段耗时违反相邻检查点严格递增约束或不小于原始完赛耗时；不写入任何数据。
 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
