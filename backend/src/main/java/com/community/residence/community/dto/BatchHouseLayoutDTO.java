package com.community.residence.community.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;
/** 明确选区的原子户型修改请求。 */
@Data @Schema(description="批量设置房屋户型")
public class BatchHouseLayoutDTO {
 @Schema(description="选中的房屋ID") @NotEmpty @Size(max=5000)
 private List<@NotNull @Positive Long> houseIds;
 @Schema(description="真实户型描述，空串表示清空") @NotNull @Size(max=50)
 private String layout;
}
