package com.community.residence.common.service;

import com.community.residence.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;
import com.community.residence.common.context.SecurityUtils;
import static org.mockito.Mockito.mockStatic;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class FileUploadImageValidationTest {
    @TempDir Path directory;
    MockedStatic<SecurityUtils> security;
    @BeforeEach void setUp() { security = mockStatic(SecurityUtils.class); security.when(SecurityUtils::getUserId).thenReturn(1L); }
    @AfterEach void closeSecurity() { security.close(); }
    private byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
    @Test void genuineImageIsStored() throws Exception {
        var result = new FileUploadService(directory.toString()).upload(new MockMultipartFile("file", "photo.png", "image/png", png()), "IMAGE");
        assertThat(result.fileUrl()).matches("/uploads/[a-f0-9]{32}\\.png");
        assertThat(directory.resolve(result.fileUrl().substring(9))).exists();
    }
    @Test void htmlRenamedPngIsRejected() {
        assertThatThrownBy(() -> new FileUploadService(directory.toString()).upload(new MockMultipartFile("file", "fake.png", "image/png", "<html>not a picture</html>".getBytes()), "IMAGE")).isInstanceOf(BusinessException.class);
    }
    @Test void imageFormatMustMatchExtension() throws Exception {
        byte[] content = png();
        assertThatThrownBy(() -> new FileUploadService(directory.toString()).upload(new MockMultipartFile("file", "fake.gif", "image/gif", content), "IMAGE")).isInstanceOf(BusinessException.class);
    }
    @Test void truncatedSignatureIsRejected() {
        assertThatThrownBy(() -> new FileUploadService(directory.toString()).upload(new MockMultipartFile("file", "fake.png", "image/png", new byte[]{(byte)137,80,78,71,13,10,26,10}), "IMAGE")).isInstanceOf(BusinessException.class);
    }
    @Test void documentUploadRemainsSupported() {
        var result = new FileUploadService(directory.toString()).upload(new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes()), "DOCUMENT");
        assertThat(result.fileType()).isEqualTo("DOCUMENT");
    }
}
