package com.example.starter.crosssection;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 断面业务异常：墓碑/未解决冲突/缺字段为 422，期望版本过期为 409，其余按 HTTP 语义。
 */
public class CrossSectionApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> conflictFields;
    private final Integer currentVersion;

    private CrossSectionApiException(HttpStatus status, String message,
                                     List<String> conflictFields, Integer currentVersion) {
        super(message);
        this.status = status;
        this.conflictFields = conflictFields;
        this.currentVersion = currentVersion;
    }

    public HttpStatus status() {
        return status;
    }

    public List<String> conflictFields() {
        return conflictFields;
    }

    public Integer currentVersion() {
        return currentVersion;
    }

    /**
     * 400：请求结构合法但业务语义不接受（如非法选择键、点序不递增）。
     */
    public static CrossSectionApiException badRequest(String message) {
        return new CrossSectionApiException(HttpStatus.BAD_REQUEST, message, null, null);
    }

    /**
     * 404：观测点、版本或成果不存在。
     */
    public static CrossSectionApiException notFound(String message) {
        return new CrossSectionApiException(HttpStatus.NOT_FOUND, message, null, null);
    }

    /**
     * 409：期望版本过期、标识异参复用等乐观锁/幂等冲突。
     */
    public static CrossSectionApiException conflict(String message, Integer currentVersion) {
        return new CrossSectionApiException(HttpStatus.CONFLICT, message, null, currentVersion);
    }

    /**
     * 409：整组三方合并字段冲突，携带冲突字段与当前版本，不写入任何部分结果（仅写未决标记）。
     */
    public static CrossSectionApiException mergeConflict(List<String> conflictFields, int currentVersion) {
        return new CrossSectionApiException(HttpStatus.CONFLICT,
                "merge conflict on fields: " + String.join(", ", conflictFields),
                List.copyOf(conflictFields), currentVersion);
    }

    /**
     * 422：引用墓碑点、点存在未解决冲突或必填字段缺失：语义不可处理，不生成成果/不变更。
     */
    public static CrossSectionApiException unprocessable(String message) {
        return new CrossSectionApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, null, null);
    }
}
