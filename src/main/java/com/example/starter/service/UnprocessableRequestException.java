package com.example.starter.service;

/**
 * 请求参数不合法（潮高区间缺口、非正时间段、吃水不合法等），映射 HTTP 422。
 */
public class UnprocessableRequestException extends RuntimeException {

    private final String code;

    /**
     * @param code    可区分的错误代码，如 TIDE_GAP、DEPTH_INSUFFICIENT
     * @param message 错误描述
     */
    public UnprocessableRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @return 错误代码
     */
    public String getCode() {
        return code;
    }
}
