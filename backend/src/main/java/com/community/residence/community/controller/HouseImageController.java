package com.community.residence.community.controller;
import com.community.residence.community.service.HouseImageService;
import com.community.residence.community.entity.HouseImage;
import com.community.residence.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
/** 管辖房屋相册管理；公开展示仅由挂牌可见性守卫返回。 */
@RestController
@RequestMapping("/api/v1/houses/{houseId}/images")
@RequiredArgsConstructor
@Tag(name="房屋相册")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class HouseImageController {
    private final HouseImageService service;
    @GetMapping @Operation(summary="读取管辖房屋相册")
    public ApiResponse<List<HouseImage>> list(@PathVariable Long houseId){
        return ApiResponse.success(service.list(houseId));
    }
    @PostMapping(consumes="multipart/form-data") @Operation(summary="上传房屋真实图片并关联")
    public ApiResponse<HouseImage> upload(@PathVariable Long houseId,@RequestParam MultipartFile file,@RequestParam(defaultValue="PHOTO")String kind){
        return ApiResponse.success(service.upload(houseId,file,kind));
    }
    @PatchMapping @Operation(summary="保存图片分类说明及完整顺序，实拍优先封面")
    public ApiResponse<List<HouseImage>> reorder(@PathVariable Long houseId,@RequestBody List<HouseImageService.Edit> edits){
        return ApiResponse.success(service.reorder(houseId,edits));
    }
    @DeleteMapping("/{imageId}") @Operation(summary="移除房屋图片关联，不物理清理共享文件")
    public ApiResponse<Void> remove(@PathVariable Long houseId,@PathVariable Long imageId){
        service.remove(houseId,imageId);
        return ApiResponse.success();
    }
}
