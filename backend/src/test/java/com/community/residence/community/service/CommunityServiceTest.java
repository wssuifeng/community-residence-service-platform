package com.community.residence.community.service;

import com.community.residence.common.constant.CommonStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.community.dto.CreateCommunityDTO;
import com.community.residence.community.entity.Community;
import com.community.residence.community.mapper.CommunityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 社区业务逻辑测试：创建默认状态、停用社区引用拦截、不存在 404 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityService 单元测试")
class CommunityServiceTest {

    @Mock
    private CommunityMapper communityMapper;

    @InjectMocks
    private CommunityService communityService;

    private Community community;

    @BeforeEach
    void setUp() {
        community = new Community();
        community.setId(1L);
        community.setName("Test Community");
        community.setStatus(CommonStatus.ACTIVE);
    }

    @Test
    @DisplayName("创建社区：默认 ACTIVE 状态并写入名称地址")
    void create_shouldSetActiveStatus() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            CreateCommunityDTO dto = new CreateCommunityDTO();
            dto.setName("New Community");
            dto.setAddress("Address 1");
            when(communityMapper.insert(any(Community.class))).thenAnswer(inv -> {
                inv.getArgument(0, Community.class).setId(100L);
                return 1;
            });

            var vo = communityService.create(dto);

            assertThat(vo.getStatus()).isEqualTo("ACTIVE");
            assertThat(vo.getName()).isEqualTo("New Community");
        }
    }

    @Test
    @DisplayName("查询社区不存在：抛 404 ResourceNotFoundException")
    void getById_notFound_throws() {
        when(communityMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> communityService.getById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("社区不存在");
    }

    @Test
    @DisplayName("引用校验：停用社区不可创建子资源（COMMUNITY_INACTIVE 5101）")
    void requireActiveCommunity_inactive_throws() {
        community.setStatus(CommonStatus.INACTIVE);
        when(communityMapper.selectById(anyLong())).thenReturn(community);
        assertThatThrownBy(() -> communityService.requireActiveCommunity(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.COMMUNITY_INACTIVE));
    }

    @Test
    @DisplayName("更新社区状态：INACTIVE 写入并留痕操作人")
    void updateStatus_writesNewStatus() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(anyString())).thenReturn(true);
            when(communityMapper.selectById(1L)).thenReturn(community);
            when(communityMapper.updateById(any(Community.class))).thenReturn(1);

            communityService.updateStatus(1L, CommonStatus.INACTIVE);

            assertThat(community.getStatus()).isEqualTo("INACTIVE");
        }
    }

    @Test
    @DisplayName("更新社区：不存在抛 404")
    void update_notFound_throws() {
        when(communityMapper.selectById(anyLong())).thenReturn(null);
        CreateCommunityDTO dto = new CreateCommunityDTO();
        dto.setName("x");
        dto.setAddress("y");
        assertThatThrownBy(() -> communityService.update(1L, dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
