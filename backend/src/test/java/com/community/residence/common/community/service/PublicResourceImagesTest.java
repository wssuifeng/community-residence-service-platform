package com.community.residence.common.community.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.community.dto.CreateResourceDTO;
import com.community.residence.common.community.entity.Community;
import com.community.residence.common.community.entity.PublicResource;
import com.community.residence.common.community.mapper.PublicResourceMapper;
import com.community.residence.common.community.vo.ResourceVO;
import com.community.residence.common.reservation.mapper.ResourceReservationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R75 公共资源展示图片（V29，与 housing.images 同口径）：
 * ①创建带图 → 库内逗号分隔存储、响应返回数组；②逗号分隔正确拆分（含项空白清理）；
 * ③空 / 无图 → 空数组（不报错、不返回 null）；④更新全量覆盖图片；⑤总长超 1000 字符拒绝。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("公共资源展示图片（R75）")
class PublicResourceImagesTest {

    private static final Long COMMUNITY_ID = 1L;

    @Mock
    private PublicResourceMapper resourceMapper;
    @Mock
    private ResourceReservationMapper reservationMapper;
    @Mock
    private CommunityService communityService;

    @org.mockito.Spy
    private ResourceImagePolicy imagePolicy = new ResourceImagePolicy(org.mockito.Mockito.mock(com.community.residence.common.upload.service.FileUploadService.class));

    @InjectMocks
    private PublicResourceService service;

    private CreateResourceDTO dto(String[] images) {
        CreateResourceDTO dto = new CreateResourceDTO();
        dto.setCommunityId(COMMUNITY_ID);
        dto.setName("篮球场");
        dto.setType("SPORTS");
        dto.setImages(images);
        return dto;
    }

    private Community community() {
        Community community = new Community();
        community.setId(COMMUNITY_ID);
        community.setName("幸福小区");
        return community;
    }

    private PublicResource resource(Long id, String images) {
        PublicResource resource = new PublicResource();
        resource.setId(id);
        resource.setCommunityId(COMMUNITY_ID);
        resource.setName("篮球场");
        resource.setType("SPORTS");
        resource.setImages(images);
        return resource;
    }

    @Test
    @DisplayName("①创建带图：库内逗号分隔存储，响应返回数组")
    void create_withImages_storesJoinedAndReturnsArray() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            when(communityService.requireCommunity(COMMUNITY_ID)).thenReturn(community());

            ResourceVO vo = service.create(dto(new String[]{"/uploads/r1.png", "/uploads/r2.png"}));

            ArgumentCaptor<PublicResource> captor = ArgumentCaptor.forClass(PublicResource.class);
            verify(resourceMapper).insert(captor.capture());
            assertThat(captor.getValue().getImages()).isEqualTo("/uploads/r1.png,/uploads/r2.png");
            assertThat(vo.getImages()).containsExactly("/uploads/r1.png", "/uploads/r2.png");
        }
    }

    @Test
    @DisplayName("③创建无图（未传 images）：库内 NULL、响应为空数组（非 null）")
    void create_withoutImages_emptyArray() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            when(communityService.requireCommunity(COMMUNITY_ID)).thenReturn(community());

            ResourceVO vo = service.create(dto(null));

            ArgumentCaptor<PublicResource> captor = ArgumentCaptor.forClass(PublicResource.class);
            verify(resourceMapper).insert(captor.capture());
            assertThat(captor.getValue().getImages()).isNull();
            assertThat(vo.getImages()).isNotNull().isEmpty();
        }
    }

    @Test
    @DisplayName("③创建全空项（如 [\"\", \"  \"]）：归一为无图（NULL + 空数组）")
    void create_blankItemsOnly_normalizesToEmpty() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            when(communityService.requireCommunity(COMMUNITY_ID)).thenReturn(community());

            ResourceVO vo = service.create(dto(new String[]{"", "  "}));

            ArgumentCaptor<PublicResource> captor = ArgumentCaptor.forClass(PublicResource.class);
            verify(resourceMapper).insert(captor.capture());
            assertThat(captor.getValue().getImages()).isNull();
            assertThat(vo.getImages()).isNotNull().isEmpty();
        }
    }

    @Test
    @DisplayName("②详情：逗号分隔正确拆分")
    void getById_splitsCommaSeparated() {
        when(resourceMapper.selectById(9L)).thenReturn(resource(9L, "/uploads/a.png,/uploads/b.png,/uploads/c.png"));
        when(communityService.requireCommunity(COMMUNITY_ID)).thenReturn(community());

        ResourceVO vo = service.getById(9L);

        assertThat(vo.getImages()).containsExactly("/uploads/a.png", "/uploads/b.png", "/uploads/c.png");
    }

    @Test
    @DisplayName("②详情：空项与项首尾空白被清理（不产出空串元素）")
    void getById_blankItems_filtered() {
        when(resourceMapper.selectById(9L)).thenReturn(resource(9L, " /uploads/a.png ,,/uploads/b.png ,"));
        when(communityService.requireCommunity(COMMUNITY_ID)).thenReturn(community());

        ResourceVO vo = service.getById(9L);

        assertThat(vo.getImages()).containsExactly("/uploads/a.png", "/uploads/b.png");
    }

    @Test
    @DisplayName("③详情：images 为空白串 → 空数组（不报错、不返回 null）")
    void getById_blankImages_emptyArray() {
        when(resourceMapper.selectById(9L)).thenReturn(resource(9L, "   "));
        when(communityService.requireCommunity(COMMUNITY_ID)).thenReturn(community());

        assertThat(service.getById(9L).getImages()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("③详情：images 为 NULL（存量 / 无图）→ 空数组（不报错、不返回 null）")
    void getById_nullImages_emptyArray() {
        when(resourceMapper.selectById(9L)).thenReturn(resource(9L, null));
        when(communityService.requireCommunity(COMMUNITY_ID)).thenReturn(community());

        assertThat(service.getById(9L).getImages()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("④更新：图片全量覆盖（旧图被新数组替换）")
    void update_overwritesImages() {
        PublicResource existing = resource(9L, "/uploads/old.png");
        when(resourceMapper.selectById(9L)).thenReturn(existing);
        when(communityService.requireCommunity(COMMUNITY_ID)).thenReturn(community());

        ResourceVO vo;
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            vo = service.update(9L, dto(new String[]{"/uploads/new1.png", "/uploads/new2.png"}));
        }

        assertThat(existing.getImages()).isEqualTo("/uploads/new1.png,/uploads/new2.png");
        verify(resourceMapper).updateById(existing);
        assertThat(vo.getImages()).containsExactly("/uploads/new1.png", "/uploads/new2.png");
    }

    @Test
    @DisplayName("④更新：传空数组即清空图片（覆盖为无图）")
    void update_emptyArray_clearsImages() {
        PublicResource existing = resource(9L, "/uploads/old.png");
        when(resourceMapper.selectById(9L)).thenReturn(existing);
        when(communityService.requireCommunity(COMMUNITY_ID)).thenReturn(community());

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            service.update(9L, dto(new String[0]));
        }

        assertThat(existing.getImages()).isNull();
    }

    @Test
    @DisplayName("列表：分页记录逐条返回 images 数组（含无图空数组）")
    void pageByCommunity_recordsCarryImagesArray() {
        Page<PublicResource> page = new Page<>(1, 20);
        page.setRecords(List.of(resource(1L, "a.png,b.png"), resource(2L, null)));
        page.setTotal(2);
        when(resourceMapper.selectPage(any(), any())).thenReturn(page);
        when(communityService.requireCommunity(COMMUNITY_ID)).thenReturn(community());

        PageVO<ResourceVO> result;
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            result = service.pageByCommunity(COMMUNITY_ID, 1, 20, null, null);
        }

        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords().get(0).getImages()).containsExactly("a.png", "b.png");
        assertThat(result.getRecords().get(1).getImages()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("⑤图片总长超 1000 字符：拒绝（INVALID_PARAM），不落库")
    void create_imagesTooLong_rejected() {
        String longUrl = "/uploads/" + "x".repeat(1000) + ".png";

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            assertThatThrownBy(() -> service.create(dto(new String[]{longUrl})))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PARAM))
                    .hasMessageContaining("1000");
        }
        verify(resourceMapper, never()).insert(any(PublicResource.class));
    }
    @Test
    @DisplayName("图片字段更新策略必须写入NULL，避免空数组只清空返回值而未清空数据库")
    void imagesNullMustParticipateInUpdate() throws NoSuchFieldException {
        var field = PublicResource.class.getDeclaredField("images");
        var mapping = field.getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.updateStrategy()).isEqualTo(com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS);
    }

    @org.mockito.Mock
    private com.community.residence.common.community.service.CommunityResponsibleService communityResponsibleService;
}
