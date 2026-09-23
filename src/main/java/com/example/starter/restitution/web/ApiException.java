package com.example.starter.restitution.web;

/**
 * 业务异常：携带 HTTP 状态码与需要原样返回给调用方的响应体。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final transient Object body;

    public ApiException(int status, Object body) {
        super(body == null ? ("status=" + status) : body.toString());
        this.status = status;
        this.body = body;
    }

    public int getStatus() {
        return status;
    }

    public Object getBody() {
        return body;
    }
}
