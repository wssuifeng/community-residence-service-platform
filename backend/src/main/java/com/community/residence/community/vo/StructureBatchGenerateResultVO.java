package com.community.residence.community.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构链批量生成结果（部分成功语义：逐项失败原因反馈，成功项照常落库）。
 * dryRun=true 时三个 created 计数为「预计生成」数量（零写入）。
 */
@Data
@Schema(description = "结构链批量生成结果")
public class StructureBatchGenerateResultVO {

    /** 失败项层级：楼栋 */
    public static final String LEVEL_BUILDING = "BUILDING";
    /** 失败项层级：单元 */
    public static final String LEVEL_UNIT = "UNIT";
    /** 失败项层级：房屋 */
    public static final String LEVEL_HOUSE = "HOUSE";

    @Schema(description = "是否仅预览（true 时零写入）")
    private Boolean dryRun;

    @Schema(description = "新建楼栋数（dryRun 时为预计数量）")
    private Integer buildingsCreated;

    @Schema(description = "新建单元数（dryRun 时为预计数量；楼栋/单元重名跳过的不计）")
    private Integer unitsCreated;

    @Schema(description = "新建房屋数（dryRun 时为预计数量；跳过与去重的不计）")
    private Integer housesCreated;

    @Schema(description = "楼栋名预览（前 20 个）")
    private List<String> previewBuildings = new ArrayList<>();

    @Schema(description = "单元位置预览（前 20 个，形如 1号楼-1单元）")
    private List<String> previewUnits = new ArrayList<>();

    @Schema(description = "房号完整位置预览（前 50 个，形如 1号楼-1单元-101）")
    private List<String> previewHouses = new ArrayList<>();

    @Schema(description = "被跳过表达式排除的房屋数（含整层与指定房号）")
    private Integer skippedCount;

    @Schema(description = "去重剔除的房屋数（同单元内撞号：批内重复，或库内同房号已存在）")
    private Integer dedupedCount;

    @Schema(description = "失败项（level=BULDING/UNIT/HOUSE + 名称 + 原因）")
    private List<Failure> failures = new ArrayList<>();

    /** 失败项：层级 + 名称（楼栋名/单元位置/房号）+ 原因 */
    @Data
    @Schema(description = "结构链批量生成失败项")
    public static class Failure {

        @Schema(description = "层级：BUILDING-楼栋, UNIT-单元, HOUSE-房屋")
        private String level;

        @Schema(description = "对象名称（楼栋名 / 单元位置 / 房号）")
        private String name;

        @Schema(description = "失败原因")
        private String reason;

        public static Failure of(String level, String name, String reason) {
            Failure failure = new Failure();
            failure.setLevel(level);
            failure.setName(name);
            failure.setReason(reason);
            return failure;
        }
    }
}
