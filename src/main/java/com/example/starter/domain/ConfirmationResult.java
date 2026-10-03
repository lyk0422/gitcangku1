package com.example.starter.domain;

/**
 * 授粉结果确认结论。
 */
public enum ConfirmationResult {
    /** 授粉成功：固化花粉批号到花序，摘除花序也不抹除历史。 */
    SUCCESS,
    /** 授粉失败：不固化批号，释放花序，窗口未过期时可重新授粉。 */
    FAILED
}