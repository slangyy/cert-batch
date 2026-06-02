package com.certbatch.controller;

import com.certbatch.common.R;
import com.certbatch.entity.Placeholder;
import com.certbatch.entity.Template;
import com.certbatch.service.TemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/template")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    /**
     * 获取所有模板列表
     */
    @GetMapping("/list")
    public R<List<Template>> list() {
        return R.ok(templateService.list());
    }

    /**
     * 获取模板详情
     */
    @GetMapping("/{id}")
    public R<Template> detail(@PathVariable Long id) {
        return R.ok(templateService.getById(id));
    }

    /**
     * 创建模板（上传图片）
     */
    @PostMapping("/create")
    public R<Template> create(@RequestParam("name") String name,
                               @RequestParam("image") MultipartFile image) {
        try {
            Template template = templateService.createTemplate(name, image);
            return R.ok(template);
        } catch (Exception e) {
            return R.fail("创建模板失败: " + e.getMessage());
        }
    }

    /**
     * 更新模板名称
     */
    @PutMapping("/{id}/name")
    public R<Void> updateName(@PathVariable Long id, @RequestParam("name") String name) {
        templateService.updateTemplateName(id, name);
        return R.ok();
    }

    /**
     * 删除模板
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return R.ok();
    }

    /**
     * 获取模板图片
     */
    @GetMapping("/{id}/image")
    public ResponseEntity<Resource> getImage(@PathVariable Long id) {
        Path imagePath = templateService.getImagePath(id);
        if (imagePath == null || !imagePath.toFile().exists()) {
            return ResponseEntity.notFound().build();
        }
        FileSystemResource resource = new FileSystemResource(imagePath);
        String fileName = imagePath.getFileName().toString().toLowerCase();
        MediaType mediaType = fileName.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(resource);
    }

    /**
     * 获取模板的占位符列表
     */
    @GetMapping("/{id}/placeholders")
    public R<List<Placeholder>> getPlaceholders(@PathVariable Long id) {
        return R.ok(templateService.getPlaceholders(id));
    }

    /**
     * 保存模板的占位符（全量替换）
     */
    @PostMapping("/{id}/placeholders")
    public R<Void> savePlaceholders(@PathVariable Long id, @RequestBody List<Placeholder> placeholders) {
        templateService.savePlaceholders(id, placeholders);
        return R.ok();
    }
}
