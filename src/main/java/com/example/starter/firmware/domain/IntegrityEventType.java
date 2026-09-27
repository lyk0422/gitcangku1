package com.example.starter.firmware.domain;

/**
 * 完整性事件类型：CHUNK_ACCEPTED 分片接收；INTEGRITY_FAILED 完整性失败；INSTALLABLE 可安装判定。
 */
public enum IntegrityEventType {
    CHUNK_ACCEPTED,
    INTEGRITY_FAILED,
    INSTALLABLE
}
