package com.community.residence.notice.service;

import com.community.residence.common.context.SecurityUtils;
import com.community.residence.notice.entity.Notice;
import com.community.residence.notice.mapper.NoticeMapper;
import com.community.residence.notice.mapper.NoticeTargetMapper;
import com.community.residence.notice.mapper.NoticeViewRecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 公告删除顺序测试（BE-ISSUE-8）：两张子表都清理且先于父表删除 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NoticeService 删除顺序单元测试")
class NoticeServiceDeleteTest {

    @Mock
    private NoticeMapper noticeMapper;
    @Mock
    private NoticeTargetMapper noticeTargetMapper;
    @Mock
    private NoticeViewRecordMapper viewRecordMapper;

    @InjectMocks
    private NoticeService noticeService;

    @Test
    @DisplayName("删除：先清 notice_target 与 notice_view_record，再删 notice")
    void delete_clearsChildrenBeforeParent() {
        Notice draft = new Notice();
        draft.setId(1L);
        draft.setStatus("DRAFT");
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(9L);
            mocked.when(() -> SecurityUtils.hasRole("SUPER_ADMIN")).thenReturn(true);
            when(noticeMapper.selectById(1L)).thenReturn(draft);

            noticeService.delete(1L);

            InOrder order = inOrder(noticeTargetMapper, viewRecordMapper, noticeMapper);
            order.verify(noticeTargetMapper).delete(any());
            order.verify(viewRecordMapper).delete(any());
            order.verify(noticeMapper).deleteById(1L);
        }
    }
}
