package com.example.starter.pass.persist;

import java.time.Instant;

/**
 * 卫星主数据行；每行同时作为“预测修订 vs 时隙确认”竞争时的串行化锁对象。
 */
public class SatellitePo {

    /** 自增主键。 */
    private Long id;

    /** 卫星业务编码，唯一。 */
    private String code;

    /** 卫星显示名称。 */
    private String name;

    /** 行创建时间，UTC 时刻。 */
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
