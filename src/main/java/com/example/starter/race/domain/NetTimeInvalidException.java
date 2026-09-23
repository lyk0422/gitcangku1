package com.example.starter.race.domain;

/**
 * 净计时不变量被破坏时抛出：恢复事务据此整体回滚，恢复事件不落库（HTTP 422）。
 */
public class NetTimeInvalidException extends RuntimeException {

    public NetTimeInvalidException(String message) {
        super(message);
    }
}
