package com.example.starter.cue.exception;

/**
 * 请求与当前业务状态冲突（跳号回报、异参重放、并发下序号已推进等），映射 HTTP 409。
 */
public class CueConflictException extends RuntimeException {

    public CueConflictException(String message) {
        super(message);
    }
}
