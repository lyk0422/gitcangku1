package com.example.starter.session;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 批次消息中的观测命令：一条现有观测创建或合并请求，按 type 字段多态反序列化。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CreateObservationCommand.class, name = "create"),
        @JsonSubTypes.Type(value = MergeObservationCommand.class, name = "merge")
})
public sealed interface ObservationCommand permits CreateObservationCommand, MergeObservationCommand {
}