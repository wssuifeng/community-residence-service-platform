package com.community.residence.common.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.util.Iterator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传（接口设计.md §5.1）：图片 ≤5MB（jpg/jpeg/png/gif）、文档 ≤10MB（pdf/doc/docx/txt）。
 * P1 本地磁盘存储（upload.dir，默认 ./uploads），返回 /uploads/** 访问 URL，
 * 生产环境可替换为对象存储（FileUploadService 为唯一写入点）。
 */
@Slf4j
@Service
public class FileUploadService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif");
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of("pdf", "doc", "docx", "txt");
    private static final long IMAGE_MAX_BYTES = 5 * 1024 * 1024;
    private static final long DOCUMENT_MAX_BYTES = 10 * 1024 * 1024;

    private final Path storageDir;

    public FileUploadService(@Value("${upload.dir:./uploads}") String uploadDir) {
        this.storageDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new IllegalStateException("上传目录初始化失败：" + storageDir, e);
        }
    }

    /** 上传结果：fileUrl 为可直接访问的相对路径 */
    public record UploadResult(String fileId, String fileName, String fileUrl, long fileSize, String fileType) {
    }

    /** 校验并保存文件；type 非法或扩展名与类型不符抛业务异常 */
    public UploadResult upload(MultipartFile file, String type) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "上传文件不能为空");
        }
        String fileType = type == null ? "" : type.toUpperCase(Locale.ROOT);
        String extension = extensionOf(file.getOriginalFilename());
        boolean isImage = "IMAGE".equals(fileType);
        if (isImage) {
            if (!IMAGE_EXTENSIONS.contains(extension)) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "图片仅支持 jpg/jpeg/png/gif");
            }
            if (file.getSize() > IMAGE_MAX_BYTES) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "图片不能超过 5MB");
            }
            try (InputStream input = file.getInputStream()) { validateImageContent(input, extension); }
            catch (IOException exception) { throw new BusinessException(ErrorCode.INVALID_PARAM, "图片内容无法读取"); }
        } else if ("DOCUMENT".equals(fileType)) {
            if (!DOCUMENT_EXTENSIONS.contains(extension)) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "文档仅支持 pdf/doc/docx/txt");
            }
            if (file.getSize() > DOCUMENT_MAX_BYTES) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "文档不能超过 10MB");
            }
        } else {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "文件类型必须为 IMAGE 或 DOCUMENT");
        }

        String fileId = UUID.randomUUID().toString().replace("-", "");
        String storedName = fileId + (extension.isEmpty() ? "" : "." + extension);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, storageDir.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("文件保存失败：{}", e.getMessage());
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "文件保存失败");
        }
        log.info("文件已上传：{} ({} bytes, {}) operator={}",
                storedName, file.getSize(), fileType, com.community.residence.common.context.SecurityUtils.getUserId());
        return new UploadResult(fileId, file.getOriginalFilename(),
                "/uploads/" + storedName, file.getSize(), fileType);
    }

    /** 供附件关联接口复用的保存（文件类型按扩展名自动判定） */
    public UploadResult uploadAutoType(MultipartFile file) {
        String extension = extensionOf(file.getOriginalFilename());
        String type = IMAGE_EXTENSIONS.contains(extension) ? "IMAGE" : "DOCUMENT";
        return upload(file, type);
    }

    /** 校验资源新引用必须来自平台上传的真实图片；不接受路径或扩展名伪装。 */
    public void requireUploadedImage(String url) {
        if (url == null || !url.matches("/uploads/[0-9a-f]{32}\\.(jpg|jpeg|png|gif)")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "新增资源图片必须由平台上传，不支持外部URL或自定义路径");
        }
        Path file = storageDir.resolve(url.substring("/uploads/".length()));
        if (!Files.isRegularFile(file)) throw new BusinessException(ErrorCode.INVALID_PARAM, "上传图片不存在，请重新上传");
        try {
            if (Files.size(file) > IMAGE_MAX_BYTES) throw new BusinessException(ErrorCode.INVALID_PARAM, "图片不能超过5MB");
            try (InputStream input = Files.newInputStream(file)) { validateImageContent(input, extensionOf(url)); }
        } catch (IOException exception) { throw new BusinessException(ErrorCode.INVALID_PARAM, "上传图片无法读取，请重新上传"); }
    }

    /* 不信任Content-Type；读取真实格式并解码抽样像素，避免只看扩展名或伪造签名。
       抽样限制内存解码尺寸，不增加新的业务像素上限。 */
    private void validateImageContent(InputStream input, String extension) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(input)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new BusinessException(ErrorCode.INVALID_PARAM, "文件不是支持的真实图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String expected = "jpg".equals(extension) ? "jpeg" : extension;
                if (!expected.equals(format)) throw new BusinessException(ErrorCode.INVALID_PARAM, "图片真实格式与扩展名不一致");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1) throw new BusinessException(ErrorCode.INVALID_PARAM, "图片尺寸无效");
                var param = reader.getDefaultReadParam();
                param.setSourceSubsampling(Math.max(1, width / 1024), Math.max(1, height / 1024), 0, 0);
                if (reader.read(0, param) == null) throw new BusinessException(ErrorCode.INVALID_PARAM, "图片内容无效");
            } finally { reader.dispose(); }
        } catch (BusinessException exception) { throw exception; }
        catch (IOException | RuntimeException exception) { throw new BusinessException(ErrorCode.INVALID_PARAM, "图片损坏或内容无法读取"); }
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
