package com.community.residence.common.community.service;

import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.upload.service.FileUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Collections;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class PublicResourceImagePolicyTest {
    @TempDir Path directory;
    private ResourceImagePolicy policy() { return new ResourceImagePolicy(new FileUploadService(directory.toString())); }
    @Test void realPlatformImageCanBeReferenced() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB), "png", bytes);
        try (var security = mockStatic(SecurityUtils.class)) {
            var upload = new FileUploadService(directory.toString()).upload(new MockMultipartFile("file", "photo.png", "image/png", bytes.toByteArray()), "IMAGE");
            assertThat(policy().normalize(new String[]{upload.fileUrl()}, null)).isEqualTo(upload.fileUrl());
        }
    }
    @Test void newExternalAddressIsRejected() { assertThatThrownBy(() -> policy().normalize(new String[]{"https://example.com/photo.png"},null)).isInstanceOf(BusinessException.class); }
    @Test void oldExternalAddressCanOnlyBeRetained() {
        String old = "https://example.com/old.png";
        assertThat(policy().normalize(new String[]{old},old)).isEqualTo(old);
        assertThatThrownBy(() -> policy().normalize(new String[]{"https://example.com/new.png"},old)).isInstanceOf(BusinessException.class);
    }
    @Test void fabricatedPlatformAddressIsRejected() { assertThatThrownBy(() -> policy().normalize(new String[]{"/uploads/"+"a".repeat(32)+".png"},null)).isInstanceOf(BusinessException.class).hasMessageContaining("不存在"); }
    @Test void traversalIsRejected() { assertThatThrownBy(() -> policy().normalize(new String[]{"/uploads/../secret.png"},null)).isInstanceOf(BusinessException.class); }
    @Test void maximumSixImagesIsEnforced() { assertThatThrownBy(() -> policy().normalize(Collections.nCopies(7,"https://example.com/old.png").toArray(String[]::new),"https://example.com/old.png")).isInstanceOf(BusinessException.class).hasMessageContaining("6"); }
    @Test void emptyArrayClearsImages() { assertThat(policy().normalize(new String[]{"", " "},"https://example.com/old.png")).isNull(); }
}
