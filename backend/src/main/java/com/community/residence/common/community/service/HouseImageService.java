package com.community.residence.common.community.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.entity.HouseImage;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.HouseImageMapper;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.upload.service.FileUploadService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
@Service
@RequiredArgsConstructor
public class HouseImageService {
    private final HouseMapper houseMapper;
    private final HouseImageMapper imageMapper;
    private final FileUploadService uploadService;
    public record Edit(@Schema(description="图片ID") Long id,
    @Schema(description="PHOTO或FLOOR_PLAN") String kind,
    @Schema(description="说明，最多200字") String caption) {
    }
    private House require(Long id, boolean lock) {
        House h=lock?houseMapper.selectByIdForUpdate(id):houseMapper.selectById(id);
        if(h==null) throw new ResourceNotFoundException("房屋不存在");
        SecurityUtils.checkCommunityAccess(h.getCommunityId());
        return h;
    }
    public List<HouseImage> list(Long id) {
        require(id,false);
        return readImages(id);
    }
    /* 公开图片只能由经过可见性授权的挂牌装配器调用；不提供无鉴权的房屋相册接口。 */
    public List<HouseImage> readImages(Long id) {
        if(id==null) return List.of();
        List<HouseImage> rows=new ArrayList<>(imageMapper.selectList(new LambdaQueryWrapper<HouseImage>().eq(HouseImage::getHouseId,id).orderByAsc(HouseImage::getSortOrder,HouseImage::getId)));
        rows.sort(imageOrder());
        return rows;
    }
    private Comparator<HouseImage> imageOrder(){
        return Comparator.comparingInt((HouseImage i)->"PHOTO".equals(i.getKind())?0:1).thenComparing(i->i.getSortOrder()==null?0:i.getSortOrder()).thenComparing(HouseImage::getId);
    }
    public Map<Long,List<HouseImage>> readImagesBatch(List<Long> houseIds){
        if(houseIds==null||houseIds.isEmpty())return Map.of();
        Map<Long,List<HouseImage>> result=new HashMap<>();
        for(var image:imageMapper.selectList(new LambdaQueryWrapper<HouseImage>().in(HouseImage::getHouseId,houseIds)))result.computeIfAbsent(image.getHouseId(),id->new ArrayList<>()).add(image);
        result.values().forEach(rows->rows.sort(imageOrder()));
        return result;
    }
    private void kind(String value) {
        if(!Set.of("PHOTO","FLOOR_PLAN").contains(value==null?"":value)) throw new BusinessException(ErrorCode.INVALID_PARAM,"图片类型无效");
    }
    /* 锁定房屋串行上传/排序，不能并发突破上限；文件不立即物理删除以保护其他引用。 */
    @Transactional(rollbackFor=Exception.class)
    public HouseImage upload(Long id,MultipartFile file,String kind) {
        require(id,true);
        kind(kind);
        if(imageMapper.selectCount(new LambdaQueryWrapper<HouseImage>().eq(HouseImage::getHouseId,id))>=10) throw new BusinessException(ErrorCode.INVALID_PARAM,"每套房屋最多10张图片");
        var saved=uploadService.upload(file,"IMAGE");
        HouseImage image=new HouseImage();
        image.setHouseId(id);
        image.setStorageKey(saved.fileUrl());
        image.setKind(kind);
        image.setSortOrder(10);
        image.setUploadedBy(SecurityUtils.getUserId());
        imageMapper.insert(image);
        return image;
    }
    @Transactional(rollbackFor=Exception.class)
    public List<HouseImage> reorder(Long id,List<Edit> edits) {
        require(id,true);
        var existing=readImages(id);
        if(edits==null || edits.size()!=existing.size() || edits.size()>10) throw new BusinessException(ErrorCode.INVALID_PARAM,"请刷新后提交完整图片列表");
        Map<Long,HouseImage> indexed=new HashMap<>();
        existing.forEach(i->indexed.put(i.getId(),i));
        Set<Long> seen=new HashSet<>();
        for(Edit e:edits) {
            if(e==null || !indexed.containsKey(e.id()) || !seen.add(e.id())) throw new BusinessException(ErrorCode.INVALID_PARAM,"图片不属于该房屋或重复");
            kind(e.kind());
            if(e.caption()!=null&&e.caption().length()>200)throw new BusinessException(ErrorCode.INVALID_PARAM,"图片说明最多200字");
        }
        for(int n=0;n<edits.size();n++){
            var e=edits.get(n);
            var i=indexed.get(e.id());
            i.setKind(e.kind());
            i.setCaption(e.caption()==null?"":e.caption().trim());
            i.setSortOrder(n);
            imageMapper.updateById(i);
        }
        return readImages(id);
    }
    @Transactional(rollbackFor=Exception.class)
    public void remove(Long id,Long imageId) {
        require(id,true);
        HouseImage i=imageMapper.selectById(imageId);
        if(i==null||!Objects.equals(i.getHouseId(),id))throw new ResourceNotFoundException("该房屋图片不存在");
        imageMapper.deleteById(imageId);
    }
}
