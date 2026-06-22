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
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateService {

    private static final int PREVIEW_ROW_LIMIT = 10;
    private static final int PROGRESS_ROW_INTERVAL = 50;
    private static final long PROGRESS_TIME_INTERVAL_MS = 200L;
    private static final int MAX_ERROR_MESSAGES = 200;
    private static final int MAX_FILE_NAME_LENGTH = 120;

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
                    ImageIO.write(certImage, "png", pngPath.toFile());
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
