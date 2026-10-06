package com.example.starter.evidence;

/**
 * 箱独立封条状态，与交接状态、成员证物封条相互独立。
 * INTACT 完好；BROKEN 异常（终态，使根箱不可交接，不代表成员封条状态）。
 */
public enum SealStatus {
    INTACT,
    BROKEN
}
