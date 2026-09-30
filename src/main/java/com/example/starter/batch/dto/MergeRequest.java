package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求。parentKeys 为 2～10 个父批业务键：
 * 父批换序视为同一组参数（服务端按业务键排序后参与指纹），重复键返回 422，换集合返回 409。
 * 合批不要求客户端传清洁代次：实际使用并固化的代次取合批落定时全部父批的共同当前代次。
 */
public record MergeRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "childBatchKey 不能为空") String childBatchKey,
        @NotBlank(message = "childBatchNo 不能为空") String childBatchNo,
        @NotEmpty(message = "parentKeys 不能为空")
        @Size(min = 2, max = 10, message = "parentKeys 必须包含 2～10 个父批")
        List<@NotBlank(message = "父批 batchKey 不能为空") String> parentKeys
) {
}
