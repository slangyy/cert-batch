package com.certbatch.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.certbatch.entity.Placeholder;
import com.certbatch.entity.Template;
import com.certbatch.mapper.PlaceholderMapper;
import com.certbatch.mapper.TemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TemplateService extends ServiceImpl<TemplateMapper, Template> {

    private final PlaceholderMapper placeholderMapper;

    @Value("${app.data-dir}")
    private String dataDir;

    /**
     * 获取模板图片存储目录
     */
    public Path getImageDir() {
        Path dir = Paths.get(dataDir, "templates").toAbsolutePath();
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException("无法创建模板图片目录", e);
            }
        }
        return dir;
    }

    /**
     * 创建模板（上传图片）
     */
    @Transactional
    public Template createTemplate(String name, MultipartFile imageFile) throws IOException {
        // 生成唯一文件名
        String originalName = imageFile.getOriginalFilename();
        String ext = originalName != null && originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf("."))
                : ".png";
        String fileName = UUID.randomUUID() + ext;

        // 保存图片文件
        Path imagePath = getImageDir().resolve(fileName);
        imageFile.transferTo(imagePath.toFile());

        // 读取图片尺寸
        BufferedImage img = ImageIO.read(imagePath.toFile());

        // 保存模板记录
        Template template = new Template();
        template.setName(name);
        template.setImageFileName(fileName);
        template.setImageWidth(img.getWidth());
        template.setImageHeight(img.getHeight());
        template.setCreateTime(LocalDateTime.now());
        template.setUpdateTime(LocalDateTime.now());
        this.save(template);

        return template;
    }

    /**
     * 获取模板详情（含占位符列表）
     */
    public Template getTemplateDetail(Long id) {
        return this.getById(id);
    }

    /**
     * 获取模板的所有占位符
     */
    public List<Placeholder> getPlaceholders(Long templateId) {
        return placeholderMapper.selectByTemplateId(templateId);
    }

    /**
     * 保存模板占位符（全量替换）
     */
    @Transactional
    public void savePlaceholders(Long templateId, List<Placeholder> placeholders) {
        // 删除旧的占位符
        placeholderMapper.deleteByTemplateId(templateId);
        // 保存新的占位符
        for (Placeholder p : placeholders) {
            p.setId(null);
            p.setTemplateId(templateId);
            placeholderMapper.insert(p);
        }
    }

    /**
     * 删除模板
     */
    @Transactional
    public void deleteTemplate(Long id) {
        Template template = this.getById(id);
        if (template != null) {
            // 删除图片文件
            Path imagePath = getImageDir().resolve(template.getImageFileName());
            try {
                Files.deleteIfExists(imagePath);
            } catch (IOException ignored) {
            }
            // 删除占位符
            placeholderMapper.deleteByTemplateId(id);
            // 删除模板记录
            this.removeById(id);
        }
    }

    /**
     * 更新模板名称
     */
    public void updateTemplateName(Long id, String name) {
        Template template = this.getById(id);
        if (template != null) {
            template.setName(name);
            template.setUpdateTime(LocalDateTime.now());
            this.updateById(template);
        }
    }

    /**
     * 获取模板图片路径
     */
    public Path getImagePath(Long templateId) {
        Template template = this.getById(templateId);
        if (template == null) {
            return null;
        }
        return getImageDir().resolve(template.getImageFileName());
    }
}
