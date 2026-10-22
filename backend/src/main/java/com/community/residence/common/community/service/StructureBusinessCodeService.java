package com.community.residence.common.community.service;

import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.mapper.StructureBusinessCodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;

/** 人读编号与内部主键分离；同父级编号分配在父记录锁内完成。 */
@Service
@RequiredArgsConstructor
public class StructureBusinessCodeService {
    private final StructureBusinessCodeMapper mapper;
    private static final Pattern SEGMENT = Pattern.compile("[\\p{L}\\p{N}_]{1,32}");
    private static final Pattern NUMBERED_NAME = Pattern.compile("([0-9]+)(?:号楼|栋|幢|单元)?");

    public void lockExistingCommunity(Long id) { mapper.lockCommunity(id); }

    public String community(String requested) { mapper.lockSequence(); return choose(requested, null, mapper.communityCodes()); }
    public String building(Long communityId, String requested, String name, Long excludedId) {
        mapper.lockCommunity(communityId);
        return choose(requested, name, mapper.buildingCodes(communityId, excludedId));
    }
    public String unit(Long buildingId, String requested, String name, Long excludedId) {
        mapper.lockBuilding(buildingId);
        return choose(requested, name, mapper.unitCodes(buildingId, excludedId));
    }
    public String updateCommunity(String requested, Long id) {
        mapper.lockSequence(); return choose(requested, null, mapper.otherCommunityCodes(id));
    }
    /* 每段必须明确，先查唯一位置再检查管理权限；不能将三段或内部ID猜成业务编号。 */
    public String codeForHouse(Long houseId) { return mapper.codeForHouse(houseId); }
    public House resolve(String code) {
        String[] parts = code == null ? new String[0] : code.trim().split("-", -1);
        if (parts.length != 4 || java.util.Arrays.stream(parts).anyMatch(String::isBlank))
            throw new BusinessException(ErrorCode.INVALID_PARAM, "房屋业务编号必须为小区-楼栋-单元-房屋号四段");
        House house = mapper.resolve(parts[0], parts[1], parts[2], unescape(parts[3]));
        if (house == null) throw new BusinessException(ErrorCode.INVALID_PARAM, "房屋业务编号不存在或结构已变更，请查看当前业务编号");
        SecurityUtils.checkCommunityAccess(house.getCommunityId());
        return house;
    }
    public static String join(String community, String building, String unit, String room) {
        if (community == null || building == null || unit == null || room == null) return null;
        return community + "-" + building + "-" + unit + "-" + room.replace("%", "%25").replace("-", "%2D");
    }
    private static String unescape(String value) { return value.replace("%2D", "-").replace("%2d", "-").replace("%25", "%"); }
    public static String choose(String requested, String name, List<String> used) {
        if (requested != null && !requested.isBlank()) {
            String code = requested.trim();
            if (!SEGMENT.matcher(code).matches()) throw new BusinessException(ErrorCode.INVALID_PARAM, "业务编号只能含文字、数字或下划线，最多32位，不能含分隔符");
            if (used.stream().anyMatch(v -> code.equalsIgnoreCase(v))) throw new BusinessException(ErrorCode.DATA_EXISTS, "同一范围内业务编号已存在");
            return code;
        }
        var match = NUMBERED_NAME.matcher(name == null ? "" : name.trim());
        if (match.matches() && !used.contains(match.group(1))) return match.group(1);
        BigInteger next = used.stream().filter(v -> v != null && v.matches("[0-9]+"))
                .map(BigInteger::new).max(BigInteger::compareTo).orElse(BigInteger.ZERO).add(BigInteger.ONE);
        if (next.toString().length() > 32) throw new BusinessException(ErrorCode.INVALID_PARAM, "请显式指定可用的业务编号");
        return next.toString();
    }
}
