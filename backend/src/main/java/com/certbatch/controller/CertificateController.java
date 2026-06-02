package com.certbatch.controller;

import com.certbatch.common.R;
import com.certbatch.service.CertificateService;
import com.certbatch.service.TemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/certificate")
@RequiredArgsConstructor
public class CertificateController {

    private final CertificateService certificateService;
    private final TemplateService templateService;
    private final ObjectMapper objectMapper;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

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
     * 批量生成证书（SSE 流式返回进度）
     */
    @PostMapping(value = "/batch-generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter batchGenerateSse(@RequestBody Map<String, Object> request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        sseExecutor.execute(() -> {
            try {
                Long templateId = Long.valueOf(request.get("templateId").toString());
                @SuppressWarnings("unchecked")
                List<Map<String, String>> rows = (List<Map<String, String>>) request.get("rows");
                String outputDir = (String) request.get("outputDir");
                String format = (String) request.get("format");
                String fileNameField = (String) request.get("fileNameField");

                Map<String, Object> result = certificateService.batchGenerate(
                        templateId, rows, outputDir, format, fileNameField,
                        progress -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("progress")
                                        .data(objectMapper.writeValueAsString(progress)));
                            } catch (Exception e) {
                                // ignore
                            }
                        }
                );

                // 发送完成事件
                emitter.send(SseEmitter.event()
                        .name("complete")
                        .data(objectMapper.writeValueAsString(result)));
                emitter.complete();
            } catch (Exception e) {
                try {
                    R<Object> error = R.fail("批量生成失败: " + e.getMessage());
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(objectMapper.writeValueAsString(error)));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
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
