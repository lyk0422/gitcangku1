package com.example.starter.curtailment.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态、错误码与问题刻度列表，由全局异常处理器转换为响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiDtos.ProblemTick> problems;

    public ApiException(HttpStatus status, String code, String message, List<ApiDtos.ProblemTick> problems) {
        super(message);
        this.status = status;
        this.code = code;
        this.problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** 404：资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of());
    }

    /** 409：幂等异参、状态竞争或区间重叠等冲突。 */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, List.of());
    }

    /** 422：业务规则拒绝，附问题刻度。 */
    public static ApiException unprocessable(String code, String message, List<ApiDtos.ProblemTick> problems) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, problems);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ApiDtos.ProblemTick> problems() {
        return problems;
    }
}