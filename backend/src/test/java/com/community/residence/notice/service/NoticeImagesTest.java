package com.community.residence.notice.service;
import com.community.residence.notice.vo.NoticeVO;
import com.community.residence.notice.entity.Notice;
import com.community.residence.community.service.ResourceImagePolicy;
import com.community.residence.common.service.FileUploadService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class NoticeImagesTest {
 @Test void unconfiguredNoticeHasNoPlaceholder(){var notice=new Notice();assertThat(NoticeVO.from(notice).getImages()).isEmpty();}
 @Test void picturesReadFromActualStoredList(){var notice=new Notice();notice.setImages("/uploads/a.png,/uploads/b.png");assertThat(NoticeVO.from(notice).getImages()).containsExactly("/uploads/a.png","/uploads/b.png");}
 @Test void newUrlsMustComeFromValidPlatformUploadedImages(){var files=mock(FileUploadService.class);var policy=new ResourceImagePolicy(files);assertThat(policy.normalize(new String[]{"/uploads/a.png"},null)).isEqualTo("/uploads/a.png");verify(files).requireUploadedImage("/uploads/a.png");}
 @Test void explicitEmptyListClearsImagesAndEntityCanPersistNull() throws Exception {var policy=new ResourceImagePolicy(mock(FileUploadService.class));assertThat(policy.normalize(new String[]{},"/uploads/a.png")).isNull();assertThat(Notice.class.getDeclaredField("images").getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class).updateStrategy()).isEqualTo(com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS);}
}
