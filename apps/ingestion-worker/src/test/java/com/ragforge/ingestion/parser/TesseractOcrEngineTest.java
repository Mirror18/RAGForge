package com.ragforge.ingestion.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TesseractOcrEngineTest {
    private static final UUID SPACE = UUID.fromString("018f0f00-0000-7000-8000-000000000021");
    private static final UUID REVISION = UUID.fromString("018f0f00-0000-7000-8000-000000000022");
    private static final UUID ARTIFACT_ONE = UUID.fromString("018f0f00-0000-7000-8000-000000000023");
    private static final UUID ARTIFACT_TWO = UUID.fromString("018f0f00-0000-7000-8000-000000000024");

    @BeforeAll
    static void configureHeadlessAwt() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void parsesFirstSyntheticImageOnlyPdfWithRealTesseract() throws Exception {
        assertRealOcr("RAGForge OCR sample one", ARTIFACT_ONE);
    }

    @Test
    void parsesSecondSyntheticImageOnlyPdfWithRealTesseract() throws Exception {
        assertRealOcr("RAGForge OCR sample two", ARTIFACT_TWO);
    }

    private void assertRealOcr(String expectedText, UUID artifactId) throws Exception {
        Path executable = TesseractOcrEngine.resolveExecutable();
        TesseractOcrEngine engine = new TesseractOcrEngine(executable);
        byte[] content = imageOnlyPdf(expectedText);
        ParseRequest request = new ParseRequest(SPACE, REVISION, artifactId, "application/pdf", content, 1, "1.0.0");

        ParsedDocument result = new NativeDocumentParser().parse(request, engine);
        ParseReport report = result.report();

        assertThat(report.status()).isEqualTo(ParseStatus.SUCCEEDED);
        assertThat(report.ocr().status()).isEqualTo(OcrStatus.SUCCEEDED);
        assertThat(report.ocr().engine()).isEqualTo("tesseract");
        assertThat(report.ocr().engineVersion()).matches("tesseract v?5\\..*");
        assertThat(report.ocr().auditState()).isEqualTo(OcrAuditState.COMPLETED);
        assertThat(report.pageCount()).isEqualTo(1);
        assertThat(report.nativePageCount()).isZero();
        assertThat(report.ocrPageCount()).isEqualTo(1);
        assertThat(report.ocr().pages()).containsExactly(1);
        assertThat(report.extractedTextArtifactId()).isNotNull();
        assertThat(report.characterCount()).isGreaterThan(0);

        String normalized = result.extractedText().toLowerCase(Locale.ROOT);
        for (String token : expectedText.toLowerCase(Locale.ROOT).split("\\s+")) {
            assertThat(normalized).contains(token);
        }
    }

    private static byte[] imageOnlyPdf(String text) throws Exception {
        BufferedImage image = new BufferedImage(2400, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 160));
            graphics.drawString(text, 120, 420);
        } finally {
            graphics.dispose();
        }

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(612, 260));
            document.addPage(page);
            PDImageXObject imageObject = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(imageObject, 36, 40, 540, 180);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }
}
