package com.example.starter.domain;

/**
 * 制品坐标的一条来源证明（不可变快照）。
 *
 * @param id                 证明记录 ID
 * @param name               制品名称（坐标一部分）
 * @param version            制品版本号（坐标一部分）
 * @param attestationVersion 该坐标的证明版本号，从 1 递增；当前证明取最大版本
 * @param repoId             来源仓标识
 * @param digest             证明声明的构建摘要（小写十六进制）
 * @param level              证明等级
 * @param revoked            true 表示该证明已撤销（记录保留）
 */
public record Attestation(
        long id,
        String name,
        int version,
        int attestationVersion,
        String repoId,
        String digest,
        int level,
        boolean revoked) {

    /** 坐标键：name:version。 */
    public String coordinate() {
        return name + ":" + version;
    }
}
