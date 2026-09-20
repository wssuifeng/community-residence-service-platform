package com.community.residence.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.HouseStatusConstant;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.dto.CreateHouseDTO;
import com.community.residence.community.dto.StructureBatchGenerateDTO;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.Community;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.vo.BatchCreateResultVO;
import com.community.residence.community.vo.StructureBatchGenerateResultVO;
import com.community.residence.community.vo.StructureBatchGenerateResultVO.Failure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 结构链一次性批量生成（C1）：一次请求生成 社区 → 楼栋 → 单元 → 房屋 整条链，
 * 替代「楼栋一次一栋 + 前端循环逐条建单元/房屋」的多次请求编排。
 *
 * 生成规则（与前端 UnitBatchDialog / HouseBatchDialog 同一口径）：
 * 楼栋名 = 前缀 + 序号 + 后缀；单元名 = 前缀 + 序号 + 后缀（缺省后缀「单元」）；
 * 房号 = 房号前缀 + 楼层 + 补零序号（补零宽度默认 2 位，即 1 层 4 号 → 104）。
 *
 * 部分成功语义：楼栋/单元重名逐项记 failure 后继续，房屋逐行失败由
 * {@link HouseService#insertHouses} 反馈（与单单元批量创建同一套去重与校验），
 * 不整批回滚；dryRun=true 时零写入，只返回预计清单与计数（且不做重名校验）。
 * 内存判断代替逐行查库：楼栋重名由本社区楼栋名集合判定，房屋去重由批内
 * seenNumbers 判定（新建单元不存在既有房屋，见 buildPlan 注释），不做 N+1 查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructureGenerateService {

    /** 单次上限：楼栋（防误操作打爆库） */
    static final int MAX_BUILDINGS = 60;
    /** 单次上限：单元总数 */
    static final int MAX_UNITS = 600;
    /** 单次上限：房屋总数（按生成网格计，跳过项不减免上限） */
    static final int MAX_HOUSES = 5000;
    /** 预览条数上限 */
    static final int PREVIEW_BUILDINGS = 20;
    static final int PREVIEW_UNITS = 20;
    static final int PREVIEW_HOUSES = 50;
    /** 房号序号默认补零宽度（与前端门牌号规则一致：楼层 + 两位序号） */
    static final int DEFAULT_NUMBER_WIDTH = 2;
    /** 名称字段长度上限（building.name / unit.name VARCHAR(50)） */
    private static final int MAX_NAME_LENGTH = 50;
    /** 单元名缺省后缀 */
    private static final String DEFAULT_UNIT_SUFFIX = "单元";

    private final CommunityService communityService;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final HouseService houseService;

    /** 只读预览：同生成路径的参数校验与生成计划，零写入、零操作日志 */
    public StructureBatchGenerateResultVO preview(StructureBatchGenerateDTO dto) {
        return execute(dto, true);
    }

    /** 落库生成（同一事务；逐项失败不影响其余项） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE", targetType = "COMMUNITY",
            targetId = "#dto.communityId", communityId = "#dto.communityId",
            content = "'结构链批量生成：社区 ' + #dto.communityId")
    public StructureBatchGenerateResultVO generate(StructureBatchGenerateDTO dto) {
        return execute(dto, Boolean.TRUE.equals(dto.getDryRun()));
    }

    private StructureBatchGenerateResultVO execute(StructureBatchGenerateDTO dto, boolean dryRun) {
        Community community = communityService.requireActiveCommunity(dto.getCommunityId());
        SecurityUtils.checkCommunityAccess(community.getId());

        int startNo = dto.getBuildingStartNo();
        int endNo = dto.getBuildingEndNo();
        if (endNo < startNo) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "楼栋结束序号不能小于起始序号：" + startNo + "~" + endNo);
        }
        long buildingTotal = (long) endNo - startNo + 1;
        if (buildingTotal > MAX_BUILDINGS) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "本次预计楼栋 " + buildingTotal + " 栋，超过单次上限 " + MAX_BUILDINGS + " 栋");
        }
        int unitPerBuilding = nonNegative(dto.getUnitCountPerBuilding());
        int floorsPerUnit = nonNegative(dto.getFloorsPerUnit());
        int housesPerFloor = nonNegative(dto.getHousesPerFloor());
        long unitTotal = buildingTotal * unitPerBuilding;
        if (unitTotal > MAX_UNITS) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "本次预计单元 " + unitTotal + " 个，超过单次上限 " + MAX_UNITS + " 个");
        }
        long houseTotal = unitTotal * floorsPerUnit * housesPerFloor;
        if (houseTotal > MAX_HOUSES) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "本次预计房屋 " + houseTotal + " 套，超过单次上限 " + MAX_HOUSES + " 套");
        }
        /* 房屋落库字段规则由 CreateHouseDTO 定义（area @NotNull），生成房屋前早失败，
           避免整批房屋逐行报同一条字段缺失 */
        if (houseTotal > 0 && dto.getArea() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "生成房屋时建筑面积不能为空");
        }

        Plan plan = buildPlan(dto, startNo, endNo, unitPerBuilding, floorsPerUnit, housesPerFloor, houseTotal);
        if (dryRun) {
            return preview(plan);
        }
        return persist(dto, community, plan);
    }

    /* ---- 生成计划（纯内存计算，零查询） ---- */

    private Plan buildPlan(StructureBatchGenerateDTO dto, int startNo, int endNo, int unitPerBuilding,
                           int floorsPerUnit, int housesPerFloor, long houseTotal) {
        int width = dto.getHouseNumberWidth() != null && dto.getHouseNumberWidth() > 0
                ? dto.getHouseNumberWidth()
                : Math.max(DEFAULT_NUMBER_WIDTH, String.valueOf(housesPerFloor).length());
        String housePrefix = trimmed(dto.getHouseNumberPrefix());
        String buildingPrefix = trimmed(dto.getBuildingNamePrefix());
        String buildingSuffix = trimmed(dto.getBuildingNameSuffix());
        String unitPrefix = trimmed(dto.getUnitNamePrefix());
        String unitSuffix = StringUtils.hasText(dto.getUnitNameSuffix())
                ? dto.getUnitNameSuffix().trim() : DEFAULT_UNIT_SUFFIX;
        HouseNumberSkipParser.Result skip = HouseNumberSkipParser.parse(dto.getSkipItems());

        Plan plan = new Plan();
        plan.floorsPerUnit = floorsPerUnit;
        boolean withHouses = houseTotal > 0;
        for (int no = startNo; no <= endNo; no++) {
            BuildingPlan buildingPlan = new BuildingPlan(buildingPrefix + no + buildingSuffix);
            for (int unitNo = 1; unitNo <= unitPerBuilding; unitNo++) {
                UnitPlan unitPlan = new UnitPlan(unitPrefix + unitNo + unitSuffix);
                if (withHouses) {
                    /* 同单元内撞号去重与 HouseService.batchCreate 同口径：保留首次出现者。
                       新建单元不存在既有房屋（单元主键本批 insert 生成），故此处无需查库 */
                    Set<String> seenNumbers = new HashSet<>();
                    for (int floor = 1; floor <= floorsPerUnit; floor++) {
                        for (int seq = 1; seq <= housesPerFloor; seq++) {
                            String base = baseHouseNumberOf(floor, seq, width);
                            String houseNumber = housePrefix + base;
                            if (skip.matchesAny(floor, seq, base, houseNumber)) {
                                plan.skipped++;
                                continue;
                            }
                            if (!seenNumbers.add(houseNumber)) {
                                plan.deduped++;
                                continue;
                            }
                            unitPlan.houses.add(new HouseRow(floor, houseNumber));
                        }
                    }
                }
                buildingPlan.units.add(unitPlan);
            }
            plan.buildings.add(buildingPlan);
        }
        return plan;
    }

    /** 门牌号构成：楼层 + 补零序号（width 默认 2 → 1 层 4 号 = 104） */
    private String baseHouseNumberOf(int floor, int seq, int width) {
        String seqText = String.valueOf(seq);
        return floor + "0".repeat(Math.max(0, width - seqText.length())) + seqText;
    }

    /* ---- 预览（dryRun）：零写入，不做重名校验 ---- */

    private StructureBatchGenerateResultVO preview(Plan plan) {
        StructureBatchGenerateResultVO vo = new StructureBatchGenerateResultVO();
        vo.setDryRun(true);
        vo.setBuildingsCreated(plan.buildings.size());
        int units = 0;
        int houses = 0;
        for (BuildingPlan buildingPlan : plan.buildings) {
            addPreview(vo.getPreviewBuildings(), buildingPlan.name, PREVIEW_BUILDINGS);
            for (UnitPlan unitPlan : buildingPlan.units) {
                units++;
                addPreview(vo.getPreviewUnits(), position(buildingPlan.name, unitPlan.name), PREVIEW_UNITS);
                for (HouseRow row : unitPlan.houses) {
                    houses++;
                    addPreview(vo.getPreviewHouses(),
                            position(buildingPlan.name, unitPlan.name, row.houseNumber), PREVIEW_HOUSES);
                }
            }
        }
        vo.setUnitsCreated(units);
        vo.setHousesCreated(houses);
        vo.setSkippedCount(plan.skipped);
        vo.setDedupedCount(plan.deduped);
        return vo;
    }

    /* ---- 落库 ---- */

    private StructureBatchGenerateResultVO persist(StructureBatchGenerateDTO dto, Community community, Plan plan) {
        /* 楼栋重名判定用集合：一次查出本社区既有楼栋名（内存判断，避免逐栋查询） */
        Set<String> existingBuildingNames = new HashSet<>();
        buildingMapper.selectList(new LambdaQueryWrapper<Building>()
                        .select(Building::getId, Building::getName)
                        .eq(Building::getCommunityId, community.getId()))
                .forEach(building -> existingBuildingNames.add(building.getName()));

        StructureBatchGenerateResultVO vo = new StructureBatchGenerateResultVO();
        vo.setDryRun(false);
        vo.setSkippedCount(plan.skipped);
        vo.setDedupedCount(plan.deduped);
        String houseStatus = StringUtils.hasText(dto.getHouseStatus())
                ? dto.getHouseStatus() : HouseStatusConstant.VACANT;
        Set<String> batchBuildingNames = new HashSet<>();
        int buildings = 0;
        int units = 0;
        int houses = 0;
        int plannedHouses = 0;

        for (BuildingPlan buildingPlan : plan.buildings) {
            plannedHouses += buildingPlan.houseCount();
            String nameViolation = nameViolation(buildingPlan.name, "楼栋名称");
            if (existingBuildingNames.contains(buildingPlan.name) || !batchBuildingNames.add(buildingPlan.name)) {
                vo.getFailures().add(Failure.of(StructureBatchGenerateResultVO.LEVEL_BUILDING,
                        buildingPlan.name, "该社区下已存在同名楼栋，已跳过"));
                continue;
            }
            if (nameViolation != null) {
                vo.getFailures().add(Failure.of(StructureBatchGenerateResultVO.LEVEL_BUILDING,
                        buildingPlan.name, nameViolation));
                continue;
            }
            Building building = new Building();
            building.setCommunityId(community.getId());
            building.setName(buildingPlan.name);
            /* 楼层数取生成网格的楼层数；building.floors 为 NOT NULL，仅建楼栋不建房屋时
               按最小值 1 落库（可在楼栋编辑中按实际维护） */
            building.setFloors(Math.max(1, plan.floorsPerUnit));
            buildingMapper.insert(building);
            buildings++;
            addPreview(vo.getPreviewBuildings(), buildingPlan.name, PREVIEW_BUILDINGS);

            Set<String> batchUnitNames = new HashSet<>();
            for (UnitPlan unitPlan : buildingPlan.units) {
                String unitViolation = nameViolation(unitPlan.name, "单元名称");
                if (!batchUnitNames.add(unitPlan.name)) {
                    vo.getFailures().add(Failure.of(StructureBatchGenerateResultVO.LEVEL_UNIT,
                            position(buildingPlan.name, unitPlan.name), "该楼栋下已存在同名单元，已跳过"));
                    continue;
                }
                if (unitViolation != null) {
                    vo.getFailures().add(Failure.of(StructureBatchGenerateResultVO.LEVEL_UNIT,
                            position(buildingPlan.name, unitPlan.name), unitViolation));
                    continue;
                }
                Unit unit = new Unit();
                unit.setBuildingId(building.getId());
                unit.setCommunityId(community.getId());
                unit.setName(unitPlan.name);
                unitMapper.insert(unit);
                units++;
                addPreview(vo.getPreviewUnits(), position(buildingPlan.name, unitPlan.name), PREVIEW_UNITS);

                if (unitPlan.houses.isEmpty()) {
                    continue;
                }
                List<CreateHouseDTO> rows = new ArrayList<>(unitPlan.houses.size());
                for (HouseRow row : unitPlan.houses) {
                    rows.add(toHouseDto(unit.getId(), row, dto, houseStatus));
                }
                /* 复用单单元批量创建的逐行校验/去重/失败反馈（预载房号集为空集：
                   单元为本批新建，其库内房号集恒为空，传空集即跳过逐行唯一查询） */
                BatchCreateResultVO result = houseService.insertHouses(unit, rows, Set.of());
                houses += result.getSuccess();
                for (BatchCreateResultVO.Row failed : result.getRows()) {
                    if (Boolean.TRUE.equals(failed.getSuccess())) {
                        continue;
                    }
                    String houseNumber = failed.getRowNo() != null && failed.getRowNo() <= rows.size()
                            ? rows.get(failed.getRowNo() - 1).getHouseNumber() : "";
                    vo.getFailures().add(Failure.of(StructureBatchGenerateResultVO.LEVEL_HOUSE,
                            position(buildingPlan.name, unitPlan.name, houseNumber), failed.getReason()));
                }
                for (HouseRow row : unitPlan.houses) {
                    addPreview(vo.getPreviewHouses(),
                            position(buildingPlan.name, unitPlan.name, row.houseNumber), PREVIEW_HOUSES);
                }
            }
        }
        vo.setBuildingsCreated(buildings);
        vo.setUnitsCreated(units);
        vo.setHousesCreated(houses);
        log.info("结构链批量生成完成：communityId={}, buildings={}/{}, units={}, houses={}/{}, "
                        + "skipped={}, deduped={}, failures={}, operator={}",
                community.getId(), buildings, plan.buildings.size(), units, houses, plannedHouses,
                plan.skipped, plan.deduped, vo.getFailures().size(), SecurityUtils.getUserId());
        return vo;
    }

    private CreateHouseDTO toHouseDto(Long unitId, HouseRow row, StructureBatchGenerateDTO dto, String status) {
        CreateHouseDTO item = new CreateHouseDTO();
        item.setUnitId(unitId);
        item.setHouseNumber(row.houseNumber());
        item.setFloor(row.floor());
        item.setArea(dto.getArea());
        item.setRoomCount(dto.getRoomCount());
        item.setDescription(dto.getDescription());
        item.setStatus(status);
        return item;
    }

    /* ---- 小工具 ---- */

    /** 名称长度校验（楼栋/单元名 VARCHAR(50)），不合法返回提示文本 */
    private String nameViolation(String name, String label) {
        if (!StringUtils.hasText(name)) {
            return label + "不能为空";
        }
        return name.length() > MAX_NAME_LENGTH ? label + "最多 " + MAX_NAME_LENGTH + " 字符" : null;
    }

    private void addPreview(List<String> preview, String item, int limit) {
        if (preview.size() < limit) {
            preview.add(item);
        }
    }

    private String position(String... parts) {
        return String.join("-", parts);
    }

    private int nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /* ---- 计划模型 ---- */

    /** 生成计划：待落库的楼栋/单元/房屋清单 + 跳过与去重计数 */
    private static final class Plan {
        private final List<BuildingPlan> buildings = new ArrayList<>();
        private int floorsPerUnit;
        private int skipped;
        private int deduped;
    }

    private static final class BuildingPlan {
        private final String name;
        private final List<UnitPlan> units = new ArrayList<>();

        private BuildingPlan(String name) {
            this.name = name;
        }

        private int houseCount() {
            return units.stream().mapToInt(unit -> unit.houses.size()).sum();
        }
    }

    private static final class UnitPlan {
        private final String name;
        private final List<HouseRow> houses = new ArrayList<>();

        private UnitPlan(String name) {
            this.name = name;
        }
    }

    /** 单套房屋：楼层 + 最终房号（含房号前缀） */
    private record HouseRow(int floor, String houseNumber) {
    }
}
