package com.certbatch.service;

import com.certbatch.entity.Placeholder;
import com.certbatch.entity.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateService {

    private final TemplateService templateService;

    @Value("${app.data-dir}")
    private String dataDir;

    /**
     * 解析Excel文件，返回列名和数据
     */
    public Map<String, Object> parseExcel(InputStream inputStream) throws IOException {
        List<String> headers = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new IOException("Excel文件为空");
            }

            // 读取表头
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IOException("Excel文件没有表头");
            }
            DataFormatter formatter = new DataFormatter();
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                Cell cell = headerRow.getCell(i);
                String headerValue = cell != null ? formatter.formatCellValue(cell).trim() : "";
                headers.add(headerValue);
            }

            // 读取数据行
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Map<String, String> rowData = new LinkedHashMap<>();
                boolean hasData = false;
                for (int j = 0; j < headers.size(); j++) {
                    Cell cell = row.getCell(j);
                    String value = cell != null ? formatter.formatCellValue(cell).trim() : "";
                    rowData.put(headers.get(j), value);
                    if (!value.isEmpty()) hasData = true;
                }
                if (hasData) {
                    rows.add(rowData);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("headers", headers);
        result.put("rows", rows);
        return result;
    }

    /**
     * 批量生成证书
     *
     * @param templateId 模板ID
     * @param rows       数据行列表
     * @param outputDir  输出目录
     * @param format     输出格式: png / pdf / both
     * @param fileNameField 用于文件命名的字段名
     * @return 生成结果
     */
    public Map<String, Object> batchGenerate(Long templateId, List<Map<String, String>> rows,
                                              String outputDir, String format, String fileNameField) throws IOException {
        Template template = templateService.getById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("模板不存在");
        }

        List<Placeholder> placeholders = templateService.getPlaceholders(templateId);
        Path templateImagePath = templateService.getImagePath(templateId);
        if (templateImagePath == null || !Files.exists(templateImagePath)) {
            throw new IllegalArgumentException("模板图片不存在");
        }

        // 读取模板图片
        BufferedImage templateImage = ImageIO.read(templateImagePath.toFile());

        Path outPath = Paths.get(outputDir);
        if (!Files.exists(outPath)) {
            Files.createDirectories(outPath);
        }

        int success = 0;
        int fail = 0;
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> rowData = rows.get(i);
            try {
                // 生成证书图片
                BufferedImage certImage = renderCertificate(templateImage, placeholders, rowData);

                // 确定文件名
                String baseName = getFileName(rowData, fileNameField, i + 1);

                // 输出PNG
                if ("png".equals(format) || "both".equals(format)) {
                    Path pngPath = outPath.resolve(baseName + ".png");
                    ImageIO.write(certImage, "png", pngPath.toFile());
                }

                // 输出PDF
                if ("pdf".equals(format) || "both".equals(format)) {
                    Path pdfPath = outPath.resolve(baseName + ".pdf");
                    imageToPdf(certImage, pdfPath);
                }

                success++;
            } catch (Exception e) {
                fail++;
                errors.add("第" + (i + 1) + "行生成失败: " + e.getMessage());
                log.error("生成证书失败，第{}行", i + 1, e);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", rows.size());
        result.put("success", success);
        result.put("fail", fail);
        result.put("errors", errors);
        return result;
    }

    /**
     * 渲染单个证书图片
     */
    public BufferedImage renderCertificate(BufferedImage templateImage,
                                            List<Placeholder> placeholders,
                                            Map<String, String> data) {
        // 复制模板图片
        BufferedImage certImage = new BufferedImage(
                templateImage.getWidth(), templateImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = certImage.createGraphics();

        // 设置渲染质量
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // 绘制模板底图
        g2d.drawImage(templateImage, 0, 0, null);

        // 绘制每个占位符的文字
        for (Placeholder placeholder : placeholders) {
            String text = data.getOrDefault(placeholder.getName(), "");
            if (text.isEmpty()) continue;

            // 设置字体
            Font font = new Font(placeholder.getFontName(), Font.PLAIN, placeholder.getFontSize());
            g2d.setFont(font);

            // 设置颜色
            g2d.setColor(parseColor(placeholder.getFontColor()));

            // 计算绘制位置（考虑对齐方式）
            FontMetrics fm = g2d.getFontMetrics();
            int textWidth = fm.stringWidth(text);
            int x = (int) Math.round(placeholder.getPosX());
            // 修正：前端 Konva Text 的 y 是文字顶部，Java2D drawString 的 y 是基线
            // 需要加上 ascent 使两者语义一致
            int y = (int) Math.round(placeholder.getPosY()) + fm.getAscent();

            switch (placeholder.getAlignment()) {
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
     * 预览单个证书（用于前端预览效果）
     */
    public BufferedImage previewCertificate(Long templateId, Map<String, String> data) throws IOException {
        Template template = templateService.getById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("模板不存在");
        }

        List<Placeholder> placeholders = templateService.getPlaceholders(templateId);
        Path templateImagePath = templateService.getImagePath(templateId);
        BufferedImage templateImage = ImageIO.read(templateImagePath.toFile());

        return renderCertificate(templateImage, placeholders, data);
    }

    /**
     * 图片转PDF
     */
    private void imageToPdf(BufferedImage image, Path pdfPath) throws IOException {
        // 先把图片写到临时文件
        Path tempPng = Files.createTempFile("cert_", ".png");
        try {
            ImageIO.write(image, "png", tempPng.toFile());

            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(new PDRectangle(image.getWidth(), image.getHeight()));
                document.addPage(page);

                PDImageXObject pdImage = PDImageXObject.createFromFile(tempPng.toString(), document);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.drawImage(pdImage, 0, 0, image.getWidth(), image.getHeight());
                }

                document.save(pdfPath.toFile());
            }
        } finally {
            Files.deleteIfExists(tempPng);
        }
    }

    /**
     * 解析颜色字符串
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
     * 生成文件名
     */
    private String getFileName(Map<String, String> rowData, String fileNameField, int index) {
        if (fileNameField != null && !fileNameField.isEmpty()) {
            String name = rowData.get(fileNameField);
            if (name != null && !name.isEmpty()) {
                // 替换文件名中的非法字符
                return name.replaceAll("[\\\\/:*?\"<>|]", "_");
            }
        }
        return "证书_" + index;
    }
}
