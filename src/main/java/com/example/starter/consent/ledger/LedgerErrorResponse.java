package com.example.starter.consent.ledger;

/**
 * 账本统一错误响应体。
 *
 * <p>数值字段在不涉及数量语义时省略（JSON 中不出现）：
 * <ul>
 *   <li>{@code actual} 实际值；{@code required} 要求值；{@code difference} 差额（= 要求值 - 实际值）；</li>
 *   <li>{@code detail} 失败定位信息，例如跨代次对象的 recordKey 及其实际所属代次。</li>
 * </ul>
 */
public record LedgerErrorResponse(String code, String message, Long actual, Long required, Long difference,
                                  String detail) {

    public static LedgerErrorResponse of(LedgerApiException ex) {
        return new LedgerErrorResponse(ex.getCode(), ex.getMessage(),
                ex.getActual(), ex.getRequired(), ex.getDifference(), ex.getDetail());
    }
}
