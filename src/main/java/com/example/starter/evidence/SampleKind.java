package com.example.starter.evidence;

/**
 * 样件类别。
 * STANDARD 普通证物（登记为母样后可参与联合取样）；
 * ALIQUOT 联合取样成功后生成的子样，独立走原保管链，且不可再取样。
 */
public enum SampleKind {
    STANDARD,
    ALIQUOT
}
