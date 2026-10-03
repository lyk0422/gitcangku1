package com.example.starter.dive;

import org.springframework.http.HttpStatus;

/** 业务异常基类，携带HTTP状态码。 */
abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}

/** 资源不存在，404。 */
final class NotFoundException extends ApiException {

    NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}

/** 幂等键冲突（同requestId异参）或并发冲突，409。 */
final class ConflictException extends ApiException {

    ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}

/** 业务校验失败（如空点位审核），422，失败不占幂等键。 */
final class BusinessException extends ApiException {

    BusinessException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}

/** 请求参数非法，400。 */
final class BadRequestException extends ApiException {

    BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}