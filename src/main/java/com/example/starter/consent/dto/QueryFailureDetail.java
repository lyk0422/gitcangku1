package com.example.starter.consent.dto;

import com.example.starter.consent.QueryFailureCode;

/**
 * 查询失败的可定位原因明细：整单或逐对象返回实际值、要求值与差额，数量单位均为对象数。
 *
 * @param reason       稳定失败原因码
 * @param recordKey    关联对象键；整单级失败（如用途未声明、数量不一致）为 null
 * @param actualValue  实际值（如去重后对象数、剩余余额、对象实际 epoch）；不适用时为 null
 * @param requiredValue 要求值（如请求声明数量、本次所需额度、当前有效 epoch）；不适用时为 null
 * @param difference   差额＝要求值－实际值（如额度缺口）；不适用时为 null
 * @param message      可读描述
 */
public record QueryFailureDetail(QueryFailureCode reason, String recordKey,
                                 Long actualValue, Long requiredValue, Long difference, String message) {

    /**
     * 构造整单级失败明细（无关联对象）。
     */
    public static QueryFailureDetail of(QueryFailureCode reason, Long actualValue,
                                        Long requiredValue, Long difference, String message) {
        return new QueryFailureDetail(reason, null, actualValue, requiredValue, difference, message);
    }

    /**
     * 构造对象级失败明细。
     */
    public static QueryFailureDetail ofObject(QueryFailureCode reason, String recordKey,
                                              Long actualValue, Long requiredValue, String message) {
        return new QueryFailureDetail(reason, recordKey, actualValue, requiredValue, null, message);
    }
}
