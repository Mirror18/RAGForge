package com.ragforge.ingestion.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NativeDocumentParserTest {
    private static final UUID SPACE = UUID.fromString("018f0f00-0000-7000-8000-000000000011");
    private static final UUID REVISION = UUID.fromString("018f0f00-0000-7000-8000-000000000012");
    private static final UUID ARTIFACT = UUID.fromString("018f0f00-0000-7000-8000-000000000013");
    private final NativeDocumentParser parser = new NativeDocumentParser();

    @Test
    void parsesMarkdownAndTextWithoutPuttingBodyIntoParseReport() throws Exception {
        String markdown = "---\ntitle: Synthetic\n---\n# 标题\n[[目标笔记]]\n```java\nreturn 1;\n```\n|a|b|\n|-|-|\n|1|2|\n> [!NOTE] callout\n";
        ParsedDocument result = parse("text/markdown", markdown.getBytes(StandardCharsets.UTF_8));
        assertThat(result.report().status()).isEqualTo(ParseStatus.SUCCEEDED);
        assertThat(result.extractedText()).contains("title: Synthetic", "[[目标笔记]]", "return 1", "callout");
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result.report());
        assertThat(json).doesNotContain("Synthetic", "目标笔记", "return 1");

        ParsedDocument text = parse("text/plain", "中文 Unicode fixture\nsecond line".getBytes(StandardCharsets.UTF_8));
        assertThat(text.report().status()).isEqualTo(ParseStatus.SUCCEEDED);
        assertThat(text.report().characterCount()).isGreaterThan(10);
    }

    @Test
    void parsesPdfDocxPptxAndXlsxSyntheticFixtures() throws Exception {
        ParsedDocument pdf = parse("application/pdf", pdf("native PDF fixture with sufficient text quality"));
        assertThat(pdf.report().status()).isEqualTo(ParseStatus.SUCCEEDED);
        assertThat(pdf.report().pageCount()).isEqualTo(1);
        assertThat(pdf.extractedText()).contains("native PDF fixture");

        ParsedDocument docx = parse("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx("DOCX fixture paragraph"));
        assertThat(docx.report().status()).isEqualTo(ParseStatus.SUCCEEDED);
        assertThat(docx.extractedText()).contains("DOCX fixture paragraph");

        ParsedDocument pptx = parse("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                pptx("PPTX fixture slide"));
        assertThat(pptx.report().status()).isEqualTo(ParseStatus.SUCCEEDED);
        assertThat(pptx.report().pageCount()).isEqualTo(1);
        assertThat(pptx.extractedText()).contains("PPTX fixture slide");

        ParsedDocument xlsx = parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx("XLSX fixture cell"));
        assertThat(xlsx.report().status()).isEqualTo(ParseStatus.SUCCEEDED);
        assertThat(xlsx.report().pageCount()).isEqualTo(1);
        assertThat(xlsx.extractedText()).contains("XLSX fixture cell");
    }

    @Test
    void imageOnlyPdfCannotSucceedWithoutOcrAndCanSucceedWithAuditedOcr() throws Exception {
        byte[] imageOnlyPdf = blankPdf();
        ParsedDocument unavailable = parse("application/pdf", imageOnlyPdf);
        assertThat(unavailable.report().status()).isEqualTo(ParseStatus.OCR_UNAVAILABLE);
        assertThat(unavailable.report().extractedTextArtifactId()).isNull();
        assertThat(unavailable.report().ocr().triggerReason()).isEqualTo(OcrTriggerReason.IMAGE_ONLY_PDF);
        assertThat(unavailable.report().ocr().auditState()).isEqualTo(OcrAuditState.BLOCKED);

        OcrEngine engine = request -> new OcrResponse(OcrStatus.SUCCEEDED, "synthetic-ocr", "1.0",
                List.of(new OcrPageResult(1, "OCR page fixture")), null);
        ParseRequest request = request("application/pdf", imageOnlyPdf);
        ParsedDocument recognized = parser.parse(request, engine);
        assertThat(recognized.report().status()).isEqualTo(ParseStatus.SUCCEEDED);
        assertThat(recognized.report().ocrPageCount()).isEqualTo(1);
        assertThat(recognized.report().ocr().engine()).isEqualTo("synthetic-ocr");
        assertThat(recognized.report().ocr().auditState()).isEqualTo(OcrAuditState.COMPLETED);
        assertThat(recognized.extractedText()).contains("OCR page fixture");
    }

    @Test
    void ocrTimeoutRemainsObservableAsUnavailable() throws Exception {
        OcrEngine timeout = request -> { throw new OcrException("OCR_TIMEOUT"); };
        ParsedDocument result = parser.parse(request("application/pdf", blankPdf()), timeout);
        assertThat(result.report().status()).isEqualTo(ParseStatus.OCR_UNAVAILABLE);
        assertThat(result.report().errors()).contains("OCR_FAILED");
    }

    private ParsedDocument parse(String mediaType, byte[] content) {
        return parser.parse(request(mediaType, content), null);
    }

    private ParseRequest request(String mediaType, byte[] content) {
        return new ParseRequest(SPACE, REVISION, ARTIFACT, mediaType, content, 1, "1.0.0");
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text.replaceAll("[^\\x20-\\x7E]", "?"));
                stream.endText();
            }
            return documentBytes(document);
        }
    }

    private static byte[] blankPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            return documentBytes(document);
        }
    }

    private static byte[] documentBytes(PDDocument document) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }

    private static byte[] docx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(text);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pptx(String text) throws Exception {
        try (XMLSlideShow presentation = new XMLSlideShow()) {
            XSLFSlide slide = presentation.createSlide();
            slide.createTextBox().setText(text);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            presentation.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xlsx(String text) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Fixture").createRow(0).createCell(0).setCellValue(text);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
