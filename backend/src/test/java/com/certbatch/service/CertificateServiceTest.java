package com.certbatch.service;

import com.certbatch.entity.Template;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CertificateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void batchGenerateFromExcelSkipsExistingOutputInsteadOfCreatingDuplicateOnRerun() throws Exception {
        Path templateImage = tempDir.resolve("template.png");
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.WHITE.getRGB());
        ImageIO.write(image, "png", templateImage.toFile());

        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);
        Path existingCertificate = outputDir.resolve("Alice.png");
        Files.writeString(existingCertificate, "already-generated", StandardCharsets.UTF_8);

        TemplateService templateService = mock(TemplateService.class);
        Template template = new Template();
        template.setId(1L);
        when(templateService.getById(1L)).thenReturn(template);
        when(templateService.getImagePath(1L)).thenReturn(templateImage);
        when(templateService.getPlaceholders(1L)).thenReturn(java.util.List.of());

        CertificateService service = new CertificateService(templateService);

        Map<String, Object> result = service.batchGenerateFromExcel(
                1L,
                new ByteArrayInputStream(workbookWithName("Alice")),
                outputDir.toString(),
                "png",
                "name",
                progress -> {
                });

        assertThat(result.get("total")).isEqualTo(1);
        assertThat(result.get("success")).isEqualTo(1);
        assertThat(Files.readString(existingCertificate, StandardCharsets.UTF_8)).isEqualTo("already-generated");
        assertThat(outputDir.resolve("Alice_2.png")).doesNotExist();
    }

    @Test
    void miniProgramZipSkipsRowsAlreadyPresentInExistingZipOnRerun() throws Exception {
        Path templateImage = tempDir.resolve("template.png");
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.WHITE.getRGB());
        ImageIO.write(image, "png", templateImage.toFile());

        Path outputDir = tempDir.resolve("mini-output");
        TemplateService templateService = mock(TemplateService.class);
        Template template = new Template();
        template.setId(1L);
        when(templateService.getById(1L)).thenReturn(template);
        when(templateService.getImagePath(1L)).thenReturn(templateImage);
        when(templateService.getPlaceholders(1L)).thenReturn(java.util.List.of());

        CertificateService service = new CertificateService(templateService);
        byte[] dataWorkbook = workbookWithHeadersAndRows(List.of("name"), List.of(List.of("Alice"), List.of("Bob")));
        byte[] listTemplateWorkbook = workbookWithHeadersAndRows(List.of("guid", "name"), List.of());

        service.batchGenerateMiniProgramZipFromExcel(
                1L,
                new ByteArrayInputStream(dataWorkbook),
                new ByteArrayInputStream(listTemplateWorkbook),
                outputDir.toString(),
                "guid",
                "certificates",
                progress -> {
                });

        assertThat(outputDir.resolve("小程序上传包_1.zip")).exists();

        Map<String, Object> rerunResult = service.batchGenerateMiniProgramZipFromExcel(
                1L,
                new ByteArrayInputStream(dataWorkbook),
                new ByteArrayInputStream(listTemplateWorkbook),
                outputDir.toString(),
                "guid",
                "certificates",
                progress -> {
                });

        assertThat(rerunResult.get("success")).isEqualTo(2);
        assertThat(rerunResult.get("skipped")).isEqualTo(2);
        assertThat(outputDir.resolve("小程序上传包_2.zip")).doesNotExist();
    }

    private byte[] workbookWithName(String name) throws Exception {
        return workbookWithHeadersAndRows(List.of("name"), List.of(List.of(name)));
    }

    private byte[] workbookWithHeadersAndRows(List<String> headers, List<List<String>> rows) throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("data");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                List<String> values = rows.get(rowIndex);
                for (int cellIndex = 0; cellIndex < values.size(); cellIndex++) {
                    row.createCell(cellIndex).setCellValue(values.get(cellIndex));
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
