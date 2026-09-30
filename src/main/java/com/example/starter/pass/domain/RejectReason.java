package com.example.starter.pass.domain;

import org.springframework.http.HttpStatus;

/**
 * 业务拒绝原因，{@link #status} 为对外 HTTP 状态码，{@link #code} 为可区分的机器可读原因码。
 */
public enum RejectReason {

    /** 请求参数本身不合法。 */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),

    /** 资源不存在（预测、站点、申请等）。 */
    NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 站点编码已存在。 */
    STATION_ALREADY_EXISTS(HttpStatus.CONFLICT),

    /** 窗口缺口：申请窗口为空或未完整落在预测可见窗口内。 */
    WINDOW_GAP(HttpStatus.UNPROCESSABLE_ENTITY),

    /** 仰角不足：过境最低仰角未达到站点门槛。 */
    ELEVATION_TOO_LOW(HttpStatus.UNPROCESSABLE_ENTITY),

    /** 超容量：整段窗口的容量无法容纳计划数据量（单位：字节）。 */
    CAPACITY_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),

    /** 实际接收字节非法（负数或超过计划量）。 */
    ACTUAL_BYTES_INVALID(HttpStatus.UNPROCESSABLE_ENTITY),

    /** 同天线时隙重叠冲突。 */
    ANTENNA_CONFLICT(HttpStatus.CONFLICT),

    /** 预测已被修订，不能凭过期版本锁定天线。 */
    STALE_PREDICTION(HttpStatus.CONFLICT),

    /** 同一 requestId 重放但参数与首次不一致。 */
    REQUEST_PARAM_MISMATCH(HttpStatus.CONFLICT),

    /** 时隙已有终态（已接收/已取消），不能再迁移。 */
    SLOT_ALREADY_TERMINATED(HttpStatus.CONFLICT),

    /** 当前状态不是已确认，不能回报接收完成。 */
    SLOT_NOT_CONFIRMED(HttpStatus.CONFLICT),

    /** 当前状态不是待重新分配，不能执行重分配。 */
    SLOT_NOT_PENDING(HttpStatus.CONFLICT);

    private final HttpStatus status;

    RejectReason(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** 机器可读原因码，使用枚举名以保持稳定。 */
    public String code() {
        return name();
    }
}
