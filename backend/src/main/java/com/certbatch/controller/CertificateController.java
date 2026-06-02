package com.certbatch.controller;

import com.certbatch.common.R;
import com.certbatch.entity.Placeholder;
import com.certbatch.service.CertificateService;
import com.certbatch.service.TemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/certificate")
@RequiredArgsConstructor
public class CertificateController {

    private final CertificateService certificateService;
    private final TemplateService templateService;

    /**
     * 解析Excel文件，返回列名和数据预览
     */
    @PostMapping("/parse-excel")
    public R<Map<String, Object>> parseExcel(@RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> result = certificateService.parseExcel(file.getInputStream());
            return R.ok(result);
        } catch (Exception e) {
            return R.fail("解析Excel失败: " + e.getMessage());
        }
    }

    /**
     * 批量生成证书
     */
    @PostMapping("/batch-generate")
    public R<Map<String, Object>> batchGenerate(@RequestBody Map<String, Object> request) {
        try {
            Long templateId = Long.valueOf(request.get("templateId").toString());
            @SuppressWarnings("unchecked")
            List<Map<String, String>> rows = (List<Map<String, String>>) request.get("rows");
            String outputDir = (String) request.get("outputDir");
            String format = (String) request.get("format");
            String fileNameField = (String) request.get("fileNameField");

            Map<String, Object> result = certificateService.batchGenerate(templateId, rows, outputDir, format, fileNameField);
            return R.ok(result);
        } catch (Exception e) {
            return R.fail("批量生成失败: " + e.getMessage());
        }
    }

    /**
     * 预览证书效果
     */
    @PostMapping("/preview")
    public ResponseEntity<byte[]> preview(@RequestBody Map<String, Object> request) {
        try {
            Long templateId = Long.valueOf(request.get("templateId").toString());
            @SuppressWarnings("unchecked")
            Map<String, String> data = (Map<String, String>) request.get("data");

            BufferedImage image = certificateService.previewCertificate(templateId, data);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);

            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(baos.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
