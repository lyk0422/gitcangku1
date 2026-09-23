package com.example.starter.observation;

/**
 * 观测记录来源。
 */
public enum RecordOrigin {
    /**
     * 原始上报记录。
     */
    RAW,
    /**
     * 重复观测簇归并产生的主记录；主记录不得再次入簇，防止形成归并链环。
     */
    CANONICAL
}
