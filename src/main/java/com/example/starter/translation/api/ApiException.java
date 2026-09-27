package com.example.starter.translation.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态码与错误码，由全局异常处理器转换为统一错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiDtos.TermRuleView> violations;
    private final List<ApiDtos.QuorumDeficitView> deficits;
    private final Integer missingApprovals;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.TermRuleView> violations) {
        this(status, code, message, violations, null, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<ApiDtos.QuorumDeficitView> deficits, Integer missingApprovals) {
        this(status, code, message, null, deficits, missingApprovals);
    }

    private ApiException(HttpStatus status, String code, String message,
                         List<ApiDtos.TermRuleView> violations,
                         List<ApiDtos.QuorumDeficitView> deficits, Integer missingApprovals) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
        this.deficits = deficits;
        this.missingApprovals = missingApprovals;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 术语违规明细，仅术语违规 422 时非空。 */
    public List<ApiDtos.TermRuleView> violations() {
        return violations;
    }

    /** 法定人数不足明细，仅评审法定人数 422 时非空。 */
    public List<ApiDtos.QuorumDeficitView> deficits() {
        return deficits;
    }

    /** 全文档缺失评审总数（各段落语种差额之和），仅法定人数 422 时非空。 */
    public Integer missingApprovals() {
        return missingApprovals;
    }

    /** 404：资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409：版本冲突、段落冲突、冲突声明重叠或 requestId 同键异参。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /** 422：业务规则不满足（缺译、审核失效、版本不匹配、冲突区间非法等）。 */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE", message);
    }

    /** 422：译文违反术语规则，返回全部违规术语。 */
    public static ApiException termViolation(String message, List<ApiDtos.TermRuleView> violations) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TERM_VIOLATION", message, violations);
    }

    /** 422：评审法定人数不足，返回受影响段落语种明细与缺失评审总数，错误码稳定可区分。 */
    public static ApiException quorumDeficit(String message, List<ApiDtos.QuorumDeficitView> deficits,
                                             int missingApprovals) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUORUM_NOT_MET", message,
                deficits, missingApprovals);
    }

    /** 403：操作者角色或身份不满足（如解除须由不同于声明人的合规角色确认）。 */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
