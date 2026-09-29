package com.example.starter.consent.ledger;

import org.springframework.http.HttpStatus;

/**
 * 账本业务异常：除稳定业务码与 HTTP 状态外，携带“实际值 / 要求值 / 差额”与可定位的明细。
 *
 * <p>数值字段为 null 表示该类异常不涉及对应数值（null 含义稳定，不参与序列化输出）；
 * {@code detail} 用于定位失败原因，例如跨代次对象的 recordKey 与实际所属代次。
 */
public class LedgerApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Long actual;
    private final Long required;
    private final Long difference;
    private final String detail;

    public LedgerApiException(HttpStatus status, String code, String message,
                              Long actual, Long required, Long difference, String detail) {
        super(message);
        this.status = status;
        this.code = code;
        this.actual = actual;
        this.required = required;
        this.difference = difference;
        this.detail = detail;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Long getActual() {
        return actual;
    }

    public Long getRequired() {
        return required;
    }

    public Long getDifference() {
        return difference;
    }

    public String getDetail() {
        return detail;
    }

    /**
     * 构造不涉及数量差额的异常。
     */
    public static LedgerApiException of(HttpStatus status, String code, String message) {
        return new LedgerApiException(status, code, message, null, null, null, null);
    }

    /**
     * 构造携带实际值、要求值与差额（差额 = 要求值 - 实际值）的异常。
     */
    public static LedgerApiException quantity(HttpStatus status, String code, String message,
                                              long actual, long required, String detail) {
        return new LedgerApiException(status, code, message, actual, required, required - actual, detail);
    }
}
