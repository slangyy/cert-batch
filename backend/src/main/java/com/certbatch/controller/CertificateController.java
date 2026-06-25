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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/certificate")
@RequiredArgsConstructor
public class CertificateController {

    private static final long BATCH_GENERATE_TIMEOUT_MS = 2 * 60 * 60 * 1000L; // 2小时

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
        SseEmitter emitter = new SseEmitter(BATCH_GENERATE_TIMEOUT_MS);

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
     * 批量生成证书（上传Excel文件，SSE 流式返回进度）
     */
    @PostMapping(value = "/batch-generate-file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter batchGenerateFileSse(@RequestParam("templateId") Long templateId,
                                           @RequestParam("file") MultipartFile file,
                                           @RequestParam("outputDir") String outputDir,
                                           @RequestParam("format") String format,
                                           @RequestParam(value = "fileNameField", required = false) String fileNameField) {
        SseEmitter emitter = new SseEmitter(BATCH_GENERATE_TIMEOUT_MS);
        Path tempFile;
        try {
            tempFile = Files.createTempFile("cert_batch_", ".xlsx");
            file.transferTo(tempFile);
        } catch (Exception e) {
            sendError(emitter, "读取Excel文件失败: " + e.getMessage(), e);
            return emitter;
        }

        sseExecutor.execute(() -> {
            try {
                Map<String, Object> result;
                try (var inputStream = Files.newInputStream(tempFile)) {
                    result = certificateService.batchGenerateFromExcel(
                            templateId, inputStream, outputDir, format, fileNameField,
                            progress -> {
                                try {
                                    emitter.send(SseEmitter.event()
                                            .name("progress")
                                            .data(objectMapper.writeValueAsString(progress)));
                                } catch (Exception ignored) {
                                }
                            }
                    );
                }

                emitter.send(SseEmitter.event()
                        .name("complete")
                        .data(objectMapper.writeValueAsString(result)));
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, "批量生成失败: " + e.getMessage(), e);
            } finally {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        });

        return emitter;
    }

    /**
     * 预览证书效果
     */
    @PostMapping(value = "/mini-program-zip",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter miniProgramZipSse(@RequestParam("templateId") Long templateId,
                                        @RequestParam("dataFile") MultipartFile dataFile,
                                        @RequestParam("listTemplateFile") MultipartFile listTemplateFile,
                                        @RequestParam("outputDir") String outputDir,
                                        @RequestParam("guidColumn") String guidColumn,
                                        @RequestParam("certificateFolderName") String certificateFolderName) {
        SseEmitter emitter = new SseEmitter(BATCH_GENERATE_TIMEOUT_MS);
        Path tempDataFile;
        Path tempListTemplateFile;
        try {
            tempDataFile = Files.createTempFile("cert_batch_data_", ".xlsx");
            tempListTemplateFile = Files.createTempFile("cert_batch_list_template_", ".xlsx");
            dataFile.transferTo(tempDataFile);
            listTemplateFile.transferTo(tempListTemplateFile);
        } catch (Exception e) {
            sendError(emitter, "Read Excel file failed: " + e.getMessage(), e);
            return emitter;
        }

        sseExecutor.execute(() -> {
            try {
                Map<String, Object> result;
                try (var dataInputStream = Files.newInputStream(tempDataFile);
                     var listTemplateInputStream = Files.newInputStream(tempListTemplateFile)) {
                    result = certificateService.batchGenerateMiniProgramZipFromExcel(
                            templateId, dataInputStream, listTemplateInputStream, outputDir, guidColumn,
                            certificateFolderName,
                            progress -> {
                                try {
                                    emitter.send(SseEmitter.event()
                                            .name("progress")
                                            .data(objectMapper.writeValueAsString(progress)));
                                } catch (Exception ignored) {
                                }
                            }
                    );
                }

                emitter.send(SseEmitter.event()
                        .name("complete")
                        .data(objectMapper.writeValueAsString(result)));
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, "Generate mini-program upload ZIP failed: " + e.getMessage(), e);
            } finally {
                try {
                    Files.deleteIfExists(tempDataFile);
                    Files.deleteIfExists(tempListTemplateFile);
                } catch (Exception ignored) {
                }
            }
        });

        return emitter;
    }

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

    private void sendError(SseEmitter emitter, String message, Exception e) {
        try {
            R<Object> error = R.fail(message);
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(error)));
        } catch (Exception ignored) {
        }
        emitter.completeWithError(e);
    }
}
