package com.example.starter.evidence;

/**
 * 封签版本类型。
 * ORIGINAL 证物入库时的原封签（版本 1）；RESEALED 检验完成并通过新封签校验后追加的新封签版本。
 */
public enum SealVersionKind {
    ORIGINAL,
    RESEALED
}
