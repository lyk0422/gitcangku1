package com.example.starter.disclosure;

/**
 * 披露包证物封签快照。成功生成的包内证物截至时刻封签必须正常（INTACT）；
 * 诊断接口对异常证物返回 BROKEN 及可区分原因码。
 */
public enum SealSnapshot {
    INTACT,
    BROKEN
}
