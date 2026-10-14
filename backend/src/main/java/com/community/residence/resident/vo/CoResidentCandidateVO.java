package com.community.residence.resident.vo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
/** 同住登记精确检索结果，仅包含选择账号所需字段。 */
@Data
public class CoResidentCandidateVO {
    @Schema(description="居民账号ID") private Long id;
    @Schema(description="用户名") private String username;
    @Schema(description="居民姓名") private String realName;
}
