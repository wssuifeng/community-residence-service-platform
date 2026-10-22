package com.community.residence.common.community.vo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
/** 责任人候选的最小必要资料，不返回系统用户凭据。 */
@Data @Schema(description="小区责任人管理员候选")
public class ResponsibleAdminVO {
 @Schema(description="系统账号ID") private Long id;
 @Schema(description="真实姓名") private String realName;
 @Schema(description="联系电话") private String phone;
 @Schema(description="是否已绑定目标小区") private Boolean boundToCommunity;
}
