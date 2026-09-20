package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 结构链一次性批量生成请求（C1）：一次调用生成 社区 → 楼栋 → 单元 → 房屋
 * 整条链，替代前端逐层循环单建（楼栋一次一栋、房屋逐条请求）。
 * 楼栋名 = buildingNamePrefix + 序号 + buildingNameSuffix；
 * 单元名 = unitNamePrefix + 序号 + unitNameSuffix（缺省后缀「单元」，如 1单元）；
 * 房号 = houseNumberPrefix + 楼层 + 补零序号（序号宽度见 houseNumberWidth）。
 */
@Data
@Schema(description = "结构链批量生成请求")
public class StructureBatchGenerateDTO {

    @Schema(description = "所属社区ID")
    @NotNull(message = "所属社区不能为空")
    private Long communityId;

    @Schema(description = "楼栋名前缀（可空，如 C10）")
    @Size(max = 20, message = "楼栋名前缀最多 20 字符")
    private String buildingNamePrefix;

    @Schema(description = "楼栋起始序号（1 起）")
    @NotNull(message = "楼栋起始序号不能为空")
    @Min(value = 1, message = "楼栋起始序号最少为 1")
    @Max(value = 9999, message = "楼栋序号最多 9999")
    private Integer buildingStartNo;

    @Schema(description = "楼栋结束序号（不小于起始序号；与起始序号合计上限 60 栋）")
    @NotNull(message = "楼栋结束序号不能为空")
    @Min(value = 1, message = "楼栋结束序号最少为 1")
    @Max(value = 9999, message = "楼栋序号最多 9999")
    private Integer buildingEndNo;

    @Schema(description = "楼栋名后缀（可空，如 号楼）")
    @Size(max = 20, message = "楼栋名后缀最多 20 字符")
    private String buildingNameSuffix;

    @Schema(description = "每栋楼单元数（可空，默认 0 表示不生成单元；单次单元总数上限 600）")
    @Min(value = 0, message = "每栋楼单元数不能为负数")
    @Max(value = 50, message = "每栋楼单元数最多 50")
    private Integer unitCountPerBuilding;

    @Schema(description = "单元名前缀（可空）")
    @Size(max = 20, message = "单元名前缀最多 20 字符")
    private String unitNamePrefix;

    @Schema(description = "单元名后缀（可空=默认「单元」，如 1单元）")
    @Size(max = 20, message = "单元名后缀最多 20 字符")
    private String unitNameSuffix;

    @Schema(description = "每单元楼层数（可空，默认 0 表示不生成房屋；楼层 1..该值）")
    @Min(value = 0, message = "每单元楼层数不能为负数")
    @Max(value = 200, message = "每单元楼层数最多 200")
    private Integer floorsPerUnit;

    @Schema(description = "每层房号数（可空，默认 0 表示不生成房屋；序号 1..该值）")
    @Min(value = 0, message = "每层房号数不能为负数")
    @Max(value = 200, message = "每层房号数最多 200")
    private Integer housesPerFloor;

    @Schema(description = "房号前缀（可空，如 A-）")
    @Size(max = 6, message = "房号前缀最多 6 字符")
    private String houseNumberPrefix;

    @Schema(description = "房号序号补零宽度（可空=自动推断：默认 2，与前端门牌号规则一致；"
            + "每层房号数 ≥100 时放宽到实际位数）")
    @Min(value = 1, message = "房号序号补零宽度最少为 1")
    @Max(value = 6, message = "房号序号补零宽度最多 6")
    private Integer houseNumberWidth;

    @Schema(description = "跳过项（可空；与前端同一套语法：4 整层 ｜ 4:1 第4层1号 ｜ 04 所有层4号 "
            + "｜ *:4 所有层4号 ｜ 104 基础房号 ｜ A-101 完整房号；逗号/顿号/空格分隔）")
    @Size(max = 500, message = "跳过项最多 500 字符")
    private String skipItems;

    @Schema(description = "房屋状态（可空=默认 VACANT；VACANT/OCCUPIED/RESERVED/MAINTENANCE）")
    @Pattern(regexp = "^(VACANT|OCCUPIED|RESERVED|MAINTENANCE)$", message = "房屋状态不合法")
    private String houseStatus;

    @Schema(description = "建筑面积（生成房屋时必填，全部房屋统一）")
    @DecimalMin(value = "0", message = "建筑面积不能为负数")
    private BigDecimal area;

    @Schema(description = "房间数（可空，全部房屋统一）")
    @Min(value = 0, message = "房间数不能为负数")
    private Integer roomCount;

    @Schema(description = "房屋描述（可空，全部房屋统一）")
    @Size(max = 1000, message = "房屋描述最多 1000 字符")
    private String description;

    @Schema(description = "仅预览（true 时零写入，只返回预计生成清单与各类计数）")
    private Boolean dryRun;
}
