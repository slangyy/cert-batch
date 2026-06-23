package com.certbatch.service;

import com.certbatch.entity.Placeholder;
import com.certbatch.entity.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateService {

    private static final int PREVIEW_ROW_LIMIT = 10;
    private static final int PROGRESS_ROW_INTERVAL = 50;
    private static final long PROGRESS_TIME_INTERVAL_MS = 200L;
    private static final int MAX_ERROR_MESSAGES = 200;
    private static final int MAX_FILE_NAME_LENGTH = 120;
    private static final long MINI_PROGRAM_ZIP_MAX_BYTES = 500L * 1024L * 1024L;
    private static final long MINI_PROGRAM_ZIP_RESERVE_BYTES = 2L * 1024L * 1024L;
    private static final long MINI_PROGRAM_ZIP_ENTRY_OVERHEAD_BYTES = 4096L;
    private static final String MINI_PROGRAM_LIST_ENTRY = "list.xlsx";

    private final TemplateService templateService;

    @Value("${app.data-dir}")
    private String dataDir;

    @Value("${app.batch.thread-pool-size:0}")
    private int configuredThreadPoolSize;

    /**
     * 解析Excel文件，返回列名、前10条预览和总行数，避免前端持有上万条完整数据。
     */
    public Map<String, Object> parseExcel(InputStream inputStream) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = getFirstSheet(workbook);
            DataFormatter formatter = new DataFormatter();
            List<String> headers = readHeaders(sheet, formatter);
            List<Map<String, String>> previewRows = new ArrayList<>();
            int totalRows = 0;

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Map<String, String> rowData = readRow(sheet.getRow(i), headers, formatter);
                if (rowData == null) {
                    continue;
                }
                totalRows++;
                if (previewRows.size() < PREVIEW_ROW_LIMIT) {
                    previewRows.add(rowData);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("headers", headers);
            result.put("rows", previewRows);
            result.put("previewRows", previewRows);
            result.put("totalRows", totalRows);
            return result;
        }
    }

    /**
     * 从Excel输入流迭代批量生成证书，避免前后端传输和保存完整rows。
     */
    public Map<String, Object> batchGenerateFromExcel(Long templateId, InputStream inputStream,
                                                       String outputDir, String format, String fileNameField,
                                                       Consumer<Map<String, Object>> progressCallback) throws IOException {
        String normalizedFormat = normalizeFormat(format);
        Template template = templateService.getById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("模板不存在");
        }

        List<RenderPlaceholder> renderPlaceholders = preparePlaceholders(templateService.getPlaceholders(templateId));
        Path templateImagePath = templateService.getImagePath(templateId);
        if (templateImagePath == null || !Files.exists(templateImagePath)) {
            throw new IllegalArgumentException("模板图片不存在");
        }

        BufferedImage templateImage = ImageIO.read(templateImagePath.toFile());
        if (templateImage == null) {
            throw new IllegalArgumentException("模板图片无法读取");
        }
        Path outPath = prepareOutputDir(outputDir);

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = getFirstSheet(workbook);
            DataFormatter formatter = new DataFormatter();
            List<String> headers = readHeaders(sheet, formatter);
            int total = countDataRows(sheet, headers, formatter);
            return generateRows(sheet, headers, formatter, templateImage, renderPlaceholders, outPath,
                    normalizedFormat, fileNameField, total, progressCallback);
        }
    }

    /**
     * 批量生成证书（带进度回调，兼容旧接口）。
     */
    public Map<String, Object> batchGenerate(Long templateId, List<Map<String, String>> rows,
                                              String outputDir, String format, String fileNameField,
                                              Consumer<Map<String, Object>> progressCallback) throws IOException {
        String normalizedFormat = normalizeFormat(format);
        Template template = templateService.getById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("模板不存在");
        }

        List<RenderPlaceholder> renderPlaceholders = preparePlaceholders(templateService.getPlaceholders(templateId));
        Path templateImagePath = templateService.getImagePath(templateId);
        if (templateImagePath == null || !Files.exists(templateImagePath)) {
            throw new IllegalArgumentException("模板图片不存在");
        }

        BufferedImage templateImage = ImageIO.read(templateImagePath.toFile());
        if (templateImage == null) {
            throw new IllegalArgumentException("模板图片无法读取");
        }
        Path outPath = prepareOutputDir(outputDir);

        int total = rows == null ? 0 : rows.size();
        ExecutorService executor = Executors.newFixedThreadPool(resolveThreadPoolSize());
        CompletionService<RowResult> completionService = new ExecutorCompletionService<>(executor);
        ProgressReporter reporter = new ProgressReporter(total, progressCallback);
        Set<String> usedBaseNames = new HashSet<>();

        int submitted = 0;
        int inFlight = 0;
        int maxInFlight = resolveThreadPoolSize() * 4;
        BatchCounters counters = new BatchCounters();

        try {
            if (rows != null) {
                for (int i = 0; i < rows.size(); i++) {
                    Map<String, String> rowData = rows.get(i);
                    String baseName = uniqueBaseName(outPath, getFileName(rowData, fileNameField, i + 1),
                            normalizedFormat, usedBaseNames);
                    completionService.submit(generateTask(templateImage, renderPlaceholders, rowData, outPath,
                            normalizedFormat, baseName, i + 1));
                    submitted++;
                    inFlight++;
                    if (inFlight >= maxInFlight) {
                        collectOne(completionService, counters, reporter, false);
                        inFlight--;
                    }
                }
            }
            while (counters.completed < submitted) {
                collectOne(completionService, counters, reporter, false);
            }
        } finally {
            executor.shutdown();
        }

        reporter.force(counters.success, counters.fail);
        return buildResult(total, counters);
    }

    /**
     * 批量生成证书（无进度回调，兼容旧接口）。
     */
    public Map<String, Object> batchGenerate(Long templateId, List<Map<String, String>> rows,
                                              String outputDir, String format, String fileNameField) throws IOException {
        return batchGenerate(templateId, rows, outputDir, format, fileNameField, null);
    }

    /**
     * Generate mini-program upload ZIP packages. This path is intentionally separate from normal generation.
     */
    public Map<String, Object> batchGenerateMiniProgramZipFromExcel(Long templateId,
                                                                    InputStream dataInputStream,
                                                                    InputStream listTemplateInputStream,
                                                                    String outputDir,
                                                                    String guidColumn,
                                                                    String certificateFolderName,
                                                                    Consumer<Map<String, Object>> progressCallback) throws IOException {
        if (guidColumn == null || guidColumn.trim().isEmpty()) {
            throw new IllegalArgumentException("Please select the GUID column");
        }
        String normalizedFolderName = normalizeZipFolderName(certificateFolderName);
        Path outPath = prepareOutputDir(outputDir);

        Template template = templateService.getById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("Template does not exist");
        }

        List<RenderPlaceholder> renderPlaceholders = preparePlaceholders(templateService.getPlaceholders(templateId));
        Path templateImagePath = templateService.getImagePath(templateId);
        if (templateImagePath == null || !Files.exists(templateImagePath)) {
            throw new IllegalArgumentException("Template image does not exist");
        }

        BufferedImage templateImage = ImageIO.read(templateImagePath.toFile());
        if (templateImage == null) {
            throw new IllegalArgumentException("Template image cannot be read");
        }

        byte[] listTemplateBytes = listTemplateInputStream.readAllBytes();
        List<String> listHeaders = readWorkbookHeaders(listTemplateBytes);
        int guidColumnIndex = listHeaders.indexOf(guidColumn.trim());
        if (guidColumnIndex < 0) {
            throw new IllegalArgumentException("Column not found in list.xlsx: " + guidColumn);
        }

        BatchCounters counters = new BatchCounters();
        MiniProgramZipPackageWriter zipWriter = new MiniProgramZipPackageWriter(
                outPath, listTemplateBytes, listHeaders, guidColumn.trim(), normalizedFolderName);

        try (Workbook dataWorkbook = WorkbookFactory.create(dataInputStream)) {
            Sheet dataSheet = getFirstSheet(dataWorkbook);
            DataFormatter formatter = new DataFormatter();
            List<String> dataHeaders = readHeaders(dataSheet, formatter);
            int total = countDataRows(dataSheet, dataHeaders, formatter);
            ProgressReporter reporter = new ProgressReporter(total, progressCallback);
            int submitted = 0;

            for (int i = 1; i <= dataSheet.getLastRowNum(); i++) {
                Map<String, String> rowData = readRow(dataSheet.getRow(i), dataHeaders, formatter);
                if (rowData == null) {
                    continue;
                }

                int rowNumber = ++submitted;
                String guid = UUID.randomUUID().toString();
                byte[] pngBytes;
                try {
                    BufferedImage certImage = renderPreparedCertificate(templateImage, renderPlaceholders, rowData);
                    pngBytes = encodePng(certImage);
                    long recordEstimate = estimateMiniProgramRecordBytes(pngBytes.length);
                    if (recordEstimate > miniProgramPackageLimit()) {
                        throw new IOException("A single certificate image is too large to package");
                    }
                } catch (Exception e) {
                    counters.fail++;
                    if (counters.errors.size() < MAX_ERROR_MESSAGES) {
                        counters.errors.add("Row " + rowNumber + " failed: " + e.getMessage());
                    }
                    log.error("Generate mini-program certificate failed, row {}: {}", rowNumber, e.getMessage(), e);
                    reporter.force(counters.success, counters.fail);
                    continue;
                }

                zipWriter.write(new MiniProgramRecord(rowNumber, rowData, guid), pngBytes);
                counters.success++;
                reporter.force(counters.success, counters.fail);
            }

            zipWriter.finish();
        } finally {
            zipWriter.closeIncompleteQuietly();
        }

        Map<String, Object> result = buildResult(counters.success + counters.fail, counters);
        List<Map<String, Object>> zipFileResults = new ArrayList<>();
        for (Path zipFile : zipWriter.getZipFiles()) {
            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("path", zipFile.toString());
            fileInfo.put("name", zipFile.getFileName().toString());
            fileInfo.put("size", Files.size(zipFile));
            zipFileResults.add(fileInfo);
        }
        result.put("zipFiles", zipFileResults);
        return result;
    }

    /**
     * 渲染单个证书图片，保留给预览和外部兼容调用。
     */
    public BufferedImage renderCertificate(BufferedImage templateImage,
                                            List<Placeholder> placeholders,
                                            Map<String, String> data) {
        return renderPreparedCertificate(templateImage, preparePlaceholders(placeholders), data);
    }

    /**
     * 预览单个证书（用于前端预览效果）。
     */
    public BufferedImage previewCertificate(Long templateId, Map<String, String> data) throws IOException {
        Template template = templateService.getById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("模板不存在");
        }

        List<RenderPlaceholder> placeholders = preparePlaceholders(templateService.getPlaceholders(templateId));
        Path templateImagePath = templateService.getImagePath(templateId);
        BufferedImage templateImage = ImageIO.read(templateImagePath.toFile());
        if (templateImage == null) {
            throw new IllegalArgumentException("模板图片无法读取");
        }

        return renderPreparedCertificate(templateImage, placeholders, data);
    }

    private Map<String, Object> generateRows(Sheet sheet, List<String> headers, DataFormatter formatter,
                                             BufferedImage templateImage, List<RenderPlaceholder> placeholders,
                                             Path outPath, String format, String fileNameField, int total,
                                             Consumer<Map<String, Object>> progressCallback) throws IOException {
        int poolSize = resolveThreadPoolSize();
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        CompletionService<RowResult> completionService = new ExecutorCompletionService<>(executor);
        ProgressReporter reporter = new ProgressReporter(total, progressCallback);
        Set<String> usedBaseNames = new HashSet<>();
        BatchCounters counters = new BatchCounters();
        int submitted = 0;
        int inFlight = 0;
        int maxInFlight = poolSize * 4;

        try {
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Map<String, String> rowData = readRow(sheet.getRow(i), headers, formatter);
                if (rowData == null) {
                    continue;
                }
                String baseName = uniqueBaseName(outPath, getFileName(rowData, fileNameField, submitted + 1),
                        format, usedBaseNames);
                completionService.submit(generateTask(templateImage, placeholders, rowData, outPath,
                        format, baseName, submitted + 1));
                submitted++;
                inFlight++;
                if (inFlight >= maxInFlight) {
                    collectOne(completionService, counters, reporter, false);
                    inFlight--;
                }
            }

            while (counters.completed < submitted) {
                collectOne(completionService, counters, reporter, false);
            }
        } finally {
            executor.shutdown();
        }

        reporter.force(counters.success, counters.fail);
        return buildResult(total, counters);
    }

    private Callable<RowResult> generateTask(BufferedImage templateImage, List<RenderPlaceholder> placeholders,
                                             Map<String, String> rowData, Path outPath, String format,
                                             String baseName, int rowNumber) {
        return () -> {
            try {
                BufferedImage certImage = renderPreparedCertificate(templateImage, placeholders, rowData);

                if ("png".equals(format) || "both".equals(format)) {
                    Path pngPath = outPath.resolve(baseName + ".png");
                    writeOptimizedPng(certImage, pngPath);
                }

                if ("pdf".equals(format) || "both".equals(format)) {
                    Path pdfPath = outPath.resolve(baseName + ".pdf");
                    imageToPdf(certImage, pdfPath);
                }

                return RowResult.success();
            } catch (Exception e) {
                return RowResult.fail(rowNumber, e);
            }
        };
    }

    private void collectOne(CompletionService<RowResult> completionService, BatchCounters counters,
                            ProgressReporter reporter, boolean force) throws IOException {
        try {
            Future<RowResult> future = completionService.take();
            RowResult rowResult = future.get();
            counters.completed++;
            if (rowResult.success) {
                counters.success++;
            } else {
                counters.fail++;
                if (counters.errors.size() < MAX_ERROR_MESSAGES) {
                    counters.errors.add("第" + rowResult.rowNumber + "行生成失败: " + rowResult.message);
                }
                log.error("生成证书失败，第{}行: {}", rowResult.rowNumber, rowResult.message, rowResult.error);
            }
            reporter.report(counters.completed, counters.success, counters.fail, force);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("批量生成被中断", e);
        } catch (ExecutionException e) {
            throw new IOException("批量生成任务异常", e);
        }
    }

    private Map<String, Object> buildResult(int total, BatchCounters counters) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("success", counters.success);
        result.put("fail", counters.fail);
        result.put("errors", counters.errors);
        if (counters.fail > counters.errors.size()) {
            result.put("errorOmitted", counters.fail - counters.errors.size());
        }
        return result;
    }

    private BufferedImage renderPreparedCertificate(BufferedImage templateImage,
                                                   List<RenderPlaceholder> placeholders,
                                                   Map<String, String> data) {
        BufferedImage certImage = new BufferedImage(
                templateImage.getWidth(), templateImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = certImage.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.drawImage(templateImage, 0, 0, null);

        for (RenderPlaceholder placeholder : placeholders) {
            String text = data.getOrDefault(placeholder.name, "");
            if (text.isEmpty()) {
                continue;
            }

            g2d.setFont(placeholder.font);
            g2d.setColor(placeholder.color);

            FontMetrics fm = g2d.getFontMetrics();
            int textWidth = fm.stringWidth(text);
            int x = (int) Math.round(placeholder.posX);
            int y = (int) Math.round(placeholder.posY) + fm.getAscent();

            switch (placeholder.alignment) {
                case "CENTER":
                    x = x - textWidth / 2;
                    break;
                case "RIGHT":
                    x = x - textWidth;
                    break;
                case "LEFT":
                default:
                    break;
            }

            g2d.drawString(text, x, y);
        }

        g2d.dispose();
        return certImage;
    }

    /**
     * 图片转PDF，直接从内存图片创建 PDFBox 图像对象，避免每条生成临时PNG。
     */
    private void imageToPdf(BufferedImage image, Path pdfPath) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(image.getWidth(), image.getHeight()));
            document.addPage(page);

            PDImageXObject pdImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.drawImage(pdImage, 0, 0, image.getWidth(), image.getHeight());
            }

            document.save(pdfPath.toFile());
        }
    }

    private Sheet getFirstSheet(Workbook workbook) throws IOException {
        if (workbook.getNumberOfSheets() == 0) {
            throw new IOException("Excel文件为空");
        }
        Sheet sheet = workbook.getSheetAt(0);
        if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
            throw new IOException("Excel文件为空");
        }
        return sheet;
    }

    private List<String> readHeaders(Sheet sheet, DataFormatter formatter) throws IOException {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            throw new IOException("Excel文件没有表头");
        }

        List<String> headers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            String headerValue = cell != null ? formatter.formatCellValue(cell).trim() : "";
            if (headerValue.isEmpty()) {
                throw new IOException("第" + (i + 1) + "列表头不能为空");
            }
            if (!seen.add(headerValue)) {
                throw new IOException("Excel表头重复: " + headerValue);
            }
            headers.add(headerValue);
        }
        if (headers.isEmpty()) {
            throw new IOException("Excel文件没有表头");
        }
        return headers;
    }

    private Map<String, String> readRow(Row row, List<String> headers, DataFormatter formatter) {
        if (row == null) {
            return null;
        }

        Map<String, String> rowData = new LinkedHashMap<>();
        boolean hasData = false;
        for (int j = 0; j < headers.size(); j++) {
            Cell cell = row.getCell(j);
            String value = cell != null ? formatter.formatCellValue(cell).trim() : "";
            rowData.put(headers.get(j), value);
            if (!value.isEmpty()) {
                hasData = true;
            }
        }
        return hasData ? rowData : null;
    }

    private int countDataRows(Sheet sheet, List<String> headers, DataFormatter formatter) {
        int total = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            if (readRow(sheet.getRow(i), headers, formatter) != null) {
                total++;
            }
        }
        return total;
    }

    private Path prepareOutputDir(String outputDir) throws IOException {
        if (outputDir == null || outputDir.trim().isEmpty()) {
            throw new IllegalArgumentException("输出目录不能为空");
        }
        if (outputDir.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("输出目录包含非法字符");
        }

        try {
            Path outPath = Paths.get(outputDir.trim()).toAbsolutePath().normalize();
            if (Files.exists(outPath) && !Files.isDirectory(outPath)) {
                throw new IllegalArgumentException("输出路径不是目录");
            }
            Files.createDirectories(outPath);
            return outPath;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("输出目录路径非法", e);
        }
    }

    private String normalizeFormat(String format) {
        String normalized = format == null ? "png" : format.toLowerCase(Locale.ROOT).trim();
        if (!"png".equals(normalized) && !"pdf".equals(normalized) && !"both".equals(normalized)) {
            throw new IllegalArgumentException("不支持的输出格式: " + format);
        }
        return normalized;
    }

    private int resolveThreadPoolSize() {
        if (configuredThreadPoolSize > 0) {
            return configuredThreadPoolSize;
        }
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
    }

    private List<RenderPlaceholder> preparePlaceholders(List<Placeholder> placeholders) {
        List<RenderPlaceholder> result = new ArrayList<>();
        if (placeholders == null) {
            return result;
        }
        for (Placeholder placeholder : placeholders) {
            result.add(new RenderPlaceholder(
                    placeholder.getName(),
                    placeholder.getPosX() == null ? 0 : placeholder.getPosX(),
                    placeholder.getPosY() == null ? 0 : placeholder.getPosY(),
                    new Font(placeholder.getFontName(), Font.PLAIN,
                            placeholder.getFontSize() == null ? 24 : placeholder.getFontSize()),
                    parseColor(placeholder.getFontColor()),
                    placeholder.getAlignment() == null ? "LEFT" : placeholder.getAlignment()
            ));
        }
        return result;
    }

    /**
     * 解析颜色字符串。
     */
    private Color parseColor(String colorStr) {
        if (colorStr == null || colorStr.isEmpty()) {
            return Color.BLACK;
        }
        try {
            if (colorStr.startsWith("#")) {
                return Color.decode(colorStr);
            }
            return Color.decode("#" + colorStr);
        } catch (NumberFormatException e) {
            return Color.BLACK;
        }
    }

    /**
     * 生成文件名。
     */
    private String getFileName(Map<String, String> rowData, String fileNameField, int index) {
        if (fileNameField != null && !fileNameField.isEmpty()) {
            String name = rowData.get(fileNameField);
            if (name != null && !name.isEmpty()) {
                return sanitizeFileName(name);
            }
        }
        return "证书_" + index;
    }

    private String sanitizeFileName(String name) {
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        sanitized = sanitized.replaceAll("[\\s.]+$", "");
        if (sanitized.isEmpty()) {
            sanitized = "证书";
        }
        if (sanitized.length() > MAX_FILE_NAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_FILE_NAME_LENGTH);
        }
        return sanitized;
    }

    private String uniqueBaseName(Path outPath, String baseName, String format, Set<String> usedBaseNames) {
        String sanitized = sanitizeFileName(baseName);
        String candidate = sanitized;
        int suffix = 2;
        while (usedBaseNames.contains(candidate) || outputExists(outPath, candidate, format)) {
            candidate = sanitized + "_" + suffix;
            suffix++;
        }
        usedBaseNames.add(candidate);
        return candidate;
    }

    private boolean outputExists(Path outPath, String baseName, String format) {
        if ("png".equals(format) || "both".equals(format)) {
            if (Files.exists(outPath.resolve(baseName + ".png"))) {
                return true;
            }
        }
        if ("pdf".equals(format) || "both".equals(format)) {
            return Files.exists(outPath.resolve(baseName + ".pdf"));
        }
        return false;
    }

    private List<String> readWorkbookHeaders(byte[] workbookBytes) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            return readHeaders(getFirstSheet(workbook), new DataFormatter());
        }
    }

    private String normalizeZipFolderName(String folderName) {
        if (folderName == null || folderName.trim().isEmpty()) {
            throw new IllegalArgumentException("Certificate folder name is required");
        }
        String normalized = folderName.trim();
        if (normalized.indexOf('\0') >= 0 || normalized.matches(".*[\\\\/:*?\"<>|].*")) {
            throw new IllegalArgumentException("Certificate folder name contains invalid characters");
        }
        normalized = normalized.replaceAll("[\\s.]+$", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Certificate folder name is required");
        }
        return normalized;
    }

    private void addDirectoryEntry(ZipOutputStream zos, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        zos.closeEntry();
    }

    private byte[] encodePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
            if (imageOut == null) {
                throw new IOException("Cannot create PNG output stream");
            }
            writePng(image, imageOut);
            return out.toByteArray();
        }
    }

    private void writeOptimizedPng(BufferedImage image, Path pngPath) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(pngPath);
             ImageOutputStream imageOut = ImageIO.createImageOutputStream(fileOut)) {
            if (imageOut == null) {
                throw new IOException("Cannot create PNG output stream");
            }
            writePng(image, imageOut);
        }
    }

    private void writePng(BufferedImage image, ImageOutputStream imageOut) throws IOException {
        BufferedImage pngImage = stripAlphaIfOpaque(image);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            throw new IOException("No PNG writer is available");
        }

        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.0f);
            }
            writer.setOutput(imageOut);
            writer.write(null, new IIOImage(pngImage, null, null), param);
            imageOut.flush();
        } finally {
            writer.dispose();
        }
    }

    private BufferedImage stripAlphaIfOpaque(BufferedImage image) {
        if (!image.getColorModel().hasAlpha() || !isFullyOpaque(image)) {
            return image;
        }

        BufferedImage rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = rgbImage.createGraphics();
        try {
            g2d.drawImage(image, 0, 0, null);
        } finally {
            g2d.dispose();
        }
        return rgbImage;
    }

    private boolean isFullyOpaque(BufferedImage image) {
        Raster alphaRaster = image.getAlphaRaster();
        if (alphaRaster == null) {
            return true;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (alphaRaster.getSample(x, y, 0) != 255) {
                    return false;
                }
            }
        }
        return true;
    }

    private long miniProgramPackageLimit() {
        return MINI_PROGRAM_ZIP_MAX_BYTES - MINI_PROGRAM_ZIP_RESERVE_BYTES;
    }

    private long estimateMiniProgramRecordBytes(long pngSize) {
        return pngSize + MINI_PROGRAM_ZIP_ENTRY_OVERHEAD_BYTES;
    }

    private byte[] buildMiniProgramListWorkbook(byte[] templateBytes, List<MiniProgramRecord> records,
                                                List<String> listHeaders, String guidColumn) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(templateBytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = getFirstSheet(workbook);
            int columnCount = listHeaders.size();
            CellStyle[] dataStyles = new CellStyle[columnCount];
            short dataRowHeight = -1;
            Row styleRow = sheet.getRow(1);
            if (styleRow != null) {
                dataRowHeight = styleRow.getHeight();
                for (int i = 0; i < columnCount; i++) {
                    Cell styleCell = styleRow.getCell(i);
                    if (styleCell != null) {
                        dataStyles[i] = styleCell.getCellStyle();
                    }
                }
            }

            clearDataRows(sheet);

            int rowIndex = 1;
            for (MiniProgramRecord record : records) {
                Row row = sheet.createRow(rowIndex++);
                if (dataRowHeight > 0) {
                    row.setHeight(dataRowHeight);
                }
                for (int i = 0; i < columnCount; i++) {
                    String header = listHeaders.get(i);
                    String value = header.equals(guidColumn) ? record.guid : record.rowData.getOrDefault(header, "");
                    Cell cell = row.createCell(i);
                    if (dataStyles[i] != null) {
                        cell.setCellStyle(dataStyles[i]);
                    }
                    cell.setCellValue(value);
                }
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void clearDataRows(Sheet sheet) {
        for (int i = sheet.getLastRowNum(); i >= 1; i--) {
            Row row = sheet.getRow(i);
            if (row != null) {
                sheet.removeRow(row);
            }
        }
    }

    private SequencePath nextMiniProgramZipPath(Path outputDir, int startSequence) {
        int sequence = Math.max(1, startSequence);
        while (true) {
            Path candidate = outputDir.resolve("小程序上传包_" + sequence + ".zip");
            if (!Files.exists(candidate)) {
                return new SequencePath(candidate, sequence);
            }
            sequence++;
        }
    }

    private final class MiniProgramZipPackageWriter {
        private final Path outputDir;
        private final byte[] listTemplateBytes;
        private final List<String> listHeaders;
        private final String guidColumn;
        private final String imageDirEntry;
        private final List<Path> zipFiles = new ArrayList<>();
        private final List<MiniProgramRecord> currentRecords = new ArrayList<>();
        private long currentEstimate = MINI_PROGRAM_ZIP_RESERVE_BYTES;
        private int nextSequence = 1;
        private ZipOutputStream zos;
        private Path currentZipPath;

        private MiniProgramZipPackageWriter(Path outputDir, byte[] listTemplateBytes, List<String> listHeaders,
                                            String guidColumn, String certificateFolderName) {
            this.outputDir = outputDir;
            this.listTemplateBytes = listTemplateBytes;
            this.listHeaders = listHeaders;
            this.guidColumn = guidColumn;
            this.imageDirEntry = certificateFolderName + "/";
        }

        private void write(MiniProgramRecord record, byte[] pngBytes) throws IOException {
            long recordEstimate = estimateMiniProgramRecordBytes(pngBytes.length);
            if (!currentRecords.isEmpty() && currentEstimate + recordEstimate > miniProgramPackageLimit()) {
                finishCurrentPackage();
            }

            ensureOpenPackage();
            ZipEntry imageEntry = new ZipEntry(imageDirEntry + record.guid + ".png");
            zos.putNextEntry(imageEntry);
            zos.write(pngBytes);
            zos.closeEntry();

            currentRecords.add(record);
            currentEstimate += recordEstimate;
        }

        private void finish() throws IOException {
            finishCurrentPackage();
        }

        private List<Path> getZipFiles() {
            return zipFiles;
        }

        private void ensureOpenPackage() throws IOException {
            if (zos != null) {
                return;
            }
            SequencePath sequencePath = nextMiniProgramZipPath(outputDir, nextSequence);
            nextSequence = sequencePath.sequence + 1;
            currentZipPath = sequencePath.path;
            try {
                zos = new ZipOutputStream(Files.newOutputStream(currentZipPath), StandardCharsets.UTF_8);
                addDirectoryEntry(zos, imageDirEntry);
            } catch (IOException e) {
                closeIncompleteQuietly();
                throw e;
            }
        }

        private void finishCurrentPackage() throws IOException {
            if (zos == null) {
                return;
            }

            Path completedZipPath = currentZipPath;
            boolean completed = false;
            try {
                byte[] listWorkbookBytes = buildMiniProgramListWorkbook(
                        listTemplateBytes, currentRecords, listHeaders, guidColumn);
                ZipEntry listEntry = new ZipEntry(MINI_PROGRAM_LIST_ENTRY);
                zos.putNextEntry(listEntry);
                zos.write(listWorkbookBytes);
                zos.closeEntry();
                completed = true;
            } finally {
                try {
                    zos.close();
                } finally {
                    zos = null;
                    currentZipPath = null;
                    currentRecords.clear();
                    currentEstimate = MINI_PROGRAM_ZIP_RESERVE_BYTES;
                    if (!completed) {
                        Files.deleteIfExists(completedZipPath);
                    }
                }
            }

            long actualSize = Files.size(completedZipPath);
            if (actualSize > MINI_PROGRAM_ZIP_MAX_BYTES) {
                Files.deleteIfExists(completedZipPath);
                throw new IOException("ZIP package exceeds 500M: " + completedZipPath.getFileName());
            }
            zipFiles.add(completedZipPath);
        }

        private void closeIncompleteQuietly() {
            if (zos == null) {
                return;
            }
            Path incompleteZipPath = currentZipPath;
            try {
                zos.close();
            } catch (IOException ignored) {
            } finally {
                zos = null;
                currentZipPath = null;
                currentRecords.clear();
                currentEstimate = MINI_PROGRAM_ZIP_RESERVE_BYTES;
                if (incompleteZipPath != null) {
                    try {
                        Files.deleteIfExists(incompleteZipPath);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private static final class MiniProgramRecord {
        private final int rowNumber;
        private final Map<String, String> rowData;
        private final String guid;

        private MiniProgramRecord(int rowNumber, Map<String, String> rowData, String guid) {
            this.rowNumber = rowNumber;
            this.rowData = rowData;
            this.guid = guid;
        }
    }

    private static final class SequencePath {
        private final Path path;
        private final int sequence;

        private SequencePath(Path path, int sequence) {
            this.path = path;
            this.sequence = sequence;
        }
    }

    private static final class RenderPlaceholder {
        private final String name;
        private final double posX;
        private final double posY;
        private final Font font;
        private final Color color;
        private final String alignment;

        private RenderPlaceholder(String name, double posX, double posY, Font font, Color color, String alignment) {
            this.name = name;
            this.posX = posX;
            this.posY = posY;
            this.font = font;
            this.color = color;
            this.alignment = alignment;
        }
    }

    private static final class BatchCounters {
        private int completed;
        private int success;
        private int fail;
        private final List<String> errors = new ArrayList<>();
    }

    private static final class RowResult {
        private final boolean success;
        private final int rowNumber;
        private final String message;
        private final Throwable error;

        private RowResult(boolean success, int rowNumber, String message, Throwable error) {
            this.success = success;
            this.rowNumber = rowNumber;
            this.message = message;
            this.error = error;
        }

        private static RowResult success() {
            return new RowResult(true, 0, null, null);
        }

        private static RowResult fail(int rowNumber, Throwable error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return new RowResult(false, rowNumber, message, error);
        }
    }

    private static final class ProgressReporter {
        private final int total;
        private final Consumer<Map<String, Object>> callback;
        private long lastReportAt = 0L;
        private int lastReportedCurrent = 0;

        private ProgressReporter(int total, Consumer<Map<String, Object>> callback) {
            this.total = total;
            this.callback = callback;
        }

        private void report(int current, int success, int fail, boolean force) {
            if (callback == null) {
                return;
            }
            long now = System.currentTimeMillis();
            boolean rowIntervalReached = current - lastReportedCurrent >= PROGRESS_ROW_INTERVAL;
            boolean timeIntervalReached = now - lastReportAt >= PROGRESS_TIME_INTERVAL_MS;
            if (!force && current < total && !rowIntervalReached && !timeIntervalReached) {
                return;
            }
            force(current, success, fail);
        }

        private void force(int success, int fail) {
            force(success + fail, success, fail);
        }

        private void force(int current, int success, int fail) {
            if (callback == null) {
                return;
            }
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("current", current);
            progress.put("total", total);
            progress.put("success", success);
            progress.put("fail", fail);
            progress.put("percent", total == 0 ? 100 : Math.round(current * 100.0 / total));
            callback.accept(progress);
            lastReportAt = System.currentTimeMillis();
            lastReportedCurrent = current;
        }
    }
}
