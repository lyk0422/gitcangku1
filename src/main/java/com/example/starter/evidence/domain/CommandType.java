package com.example.starter.evidence.domain;

/**
 * 幂等命令类型：入库、交接发起/接受/取消、封条核验。
 */
public enum CommandType {
    INTAKE,
    TRANSFER_INITIATE,
    TRANSFER_ACCEPT,
    TRANSFER_CANCEL,
    SEAL_CHECK
}
