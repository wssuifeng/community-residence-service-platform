package com.community.residence.community.service;
import com.community.residence.community.mapper.*;
import com.community.residence.community.entity.*;
import com.community.residence.common.service.FileUploadService;
import com.community.residence.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class HouseImageServiceTest {
    @Mock HouseMapper houseMapper;
    @Mock HouseImageMapper imageMapper;
    @Mock FileUploadService upload;
    @InjectMocks HouseImageService service;
    HouseImage image(long id,String kind,int order){
        var i=new HouseImage();
        i.setId(id);
        i.setHouseId(9L);
        i.setKind(kind);
        i.setSortOrder(order);
        return i;
    }
    @Test void photosHaveCoverPriorityAndSortIsStable(){
        when(imageMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(image(1,"FLOOR_PLAN",0),image(2,"PHOTO",3),image(3,"PHOTO",1))));
        assertThat(service.readImages(9L)).extracting(HouseImage::getId).containsExactly(3L,2L,1L);
    }
    @Test void missingHouseCannotReadOrUpload(){
        assertThatThrownBy(()->service.list(99L)).isInstanceOf(com.community.residence.common.exception.ResourceNotFoundException.class);
        verifyNoInteractions(upload,imageMapper);
    }
    @Test void publicNullReferenceReturnsNoImages(){
        assertThat(service.readImages(null)).isEmpty();
        verifyNoInteractions(imageMapper);
    }
    @Test void tenImagesCannotBeExceeded(){
        var h=new House();
        h.setId(9L);
        h.setCommunityId(1L);
        when(houseMapper.selectByIdForUpdate(9L)).thenReturn(h);
        when(imageMapper.selectCount(any())).thenReturn(10L);
        try(var sec=mockStatic(com.community.residence.common.context.SecurityUtils.class)){
            assertThatThrownBy(()->service.upload(9L,null,"PHOTO")).isInstanceOf(BusinessException.class).hasMessageContaining("10");
            verifyNoInteractions(upload);
        }
    }
    @Test void otherCommunityCannotWriteAlbum(){
        var h=new House();
        h.setCommunityId(2L);
        when(houseMapper.selectByIdForUpdate(9L)).thenReturn(h);
        try(var sec=mockStatic(com.community.residence.common.context.SecurityUtils.class)){
            sec.when(()->com.community.residence.common.context.SecurityUtils.checkCommunityAccess(2L)).thenThrow(new com.community.residence.common.exception.ForbiddenException("无权访问"));
            assertThatThrownBy(()->service.upload(9L,null,"PHOTO")).isInstanceOf(com.community.residence.common.exception.ForbiddenException.class);
            verifyNoInteractions(upload,imageMapper);
        }
    }
    @Test void reorderCannotStealAnotherHouseImage(){
        var h=new House();
        h.setCommunityId(1L);
        when(houseMapper.selectByIdForUpdate(9L)).thenReturn(h);
        when(imageMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(image(1,"PHOTO",0))));
        try(var sec=mockStatic(com.community.residence.common.context.SecurityUtils.class)){
            assertThatThrownBy(()->service.reorder(9L,List.of(new HouseImageService.Edit(99L,"PHOTO","")))).isInstanceOf(BusinessException.class);
            verify(imageMapper,never()).updateById(any(HouseImage.class));
        }
    }
    @Test void duplicateImageInReorderRejectedAtomically(){
        var h=new House();
        h.setCommunityId(1L);
        when(houseMapper.selectByIdForUpdate(9L)).thenReturn(h);
        when(imageMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(image(1,"PHOTO",0),image(2,"PHOTO",1))));
        try(var sec=mockStatic(com.community.residence.common.context.SecurityUtils.class)){
            assertThatThrownBy(()->service.reorder(9L,List.of(new HouseImageService.Edit(1L,"PHOTO",""),new HouseImageService.Edit(1L,"PHOTO","")))).isInstanceOf(BusinessException.class);
            verify(imageMapper,never()).updateById(any(HouseImage.class));
        }
    }
}
