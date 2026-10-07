package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 已接收块，对应 intake_chunk 表。写入后不可改；身份比较基于解码后的字节，
 * 等价 Base64 编码（缺省填充、含空白）不形成不同内容。
 *
 * @param id          主键
 * @param intakeKey   关联分块入库业务键
 * @param offsetBytes 块起始偏移（字节），非负
 * @param lengthBytes 解码后字节长度，1至4096
 * @param data        解码后的原始字节（合成数据），不写入日志
 * @param createdAt   块接收时间（Asia/Shanghai）
 */
public record IntakeChunk(
        Long id,
        String intakeKey,
        int offsetBytes,
        int lengthBytes,
        byte[] data,
        LocalDateTime createdAt) {
}
