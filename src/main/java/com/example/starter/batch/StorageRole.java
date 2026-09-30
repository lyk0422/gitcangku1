package com.example.starter.batch;

/**
 * 偏差处置确认角色：WAREHOUSE 仓储员（登记读数）、QUALITY 质量负责人（处理偏差）；
 * 同一偏差的两次确认必须由两种不同角色、两名不同人员完成。
 */
public enum StorageRole {
    WAREHOUSE,
    QUALITY
}
