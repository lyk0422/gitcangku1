package com.example.starter.cue.exception;

import java.util.List;

/**
 * 提示点编排或冻结参数非法（时长越界、序号重复/不连续等），映射 HTTP 400。
 * 整版本校验失败时携带全部错误原因，调用方不得只丢弃部分坏提示点。
 */
public class CueValidationException extends RuntimeException {

    private final List<String> errors;

    public CueValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
