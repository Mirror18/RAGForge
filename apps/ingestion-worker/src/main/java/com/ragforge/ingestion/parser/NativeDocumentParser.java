package com.ragforge.ingestion.parser;

import com.ragforge.ingestion.common.UuidV7;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class NativeDocumentParser implements DocumentParser {
    public static final String PARSER_NAME = "ragforge-native-parser";
    private static final String PARSER_VERSION = "1.0.0";

    @Override
    public ParsedDocument parse(ParseRequest request, OcrEngine ocrEngine) {
        Instant started = Instant.now();
        try {
            NativeResult nativeResult = switch (request.mediaType().toLowerCase(Locale.ROOT)) {
                case "text/markdown", "text/x-markdown" -> text(request, true);
                case "text/plain" -> text(request, false);
                case "application/pdf" -> pdf(request);
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> docx(request);
                case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> pptx(request);
                case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> xlsx(request);
                default -> throw new ParserException("UNSUPPORTED_MEDIA_TYPE");
            };
            if (nativeResult.text().isBlank() && nativeResult.ocrPages().isEmpty()
                    && request.mediaType().equalsIgnoreCase("application/pdf") && nativeResult.pageCount() > 0) {
                return handleOcr(request, ocrEngine, nativeResult, started, OcrTriggerReason.IMAGE_ONLY_PDF);
            }
            if (request.mediaType().equalsIgnoreCase("application/pdf")
                    && nativeResult.pageCount() > 0 && nativeResult.text().strip().length() < 20) {
                return handleOcr(request, ocrEngine, nativeResult, started, OcrTriggerReason.LOW_TEXT_QUALITY);
            }
            return success(request, nativeResult.text(), nativeResult, started,
                    ParseReport.OcrReport.notRequested(request.artifactId()));
        } catch (ParserException exception) {
            return failure(request, started, exception.getMessage());
        } catch (IOException | RuntimeException exception) {
            return failure(request, started, "PARSER_FAILED");
        }
    }

    private ParsedDocument handleOcr(ParseRequest request, OcrEngine ocrEngine, NativeResult nativeResult,
                                     Instant started, OcrTriggerReason reason) {
        List<Integer> pages = new ArrayList<>();
        for (int page = 1; page <= nativeResult.pageCount(); page++) {
            pages.add(page);
        }
        if (ocrEngine == null) {
            return ocrUnavailable(request, nativeResult, started, reason, "OCR_UNAVAILABLE");
        }
        OcrResponse response;
        try {
            response = ocrEngine.recognize(new OcrRequest(request.spaceId(), request.artifactId(), request.mediaType(),
                    pages, reason, request.content()));
        } catch (OcrException exception) {
            return ocrUnavailable(request, nativeResult, started, reason, "OCR_FAILED");
        }
        if (response.status() != OcrStatus.SUCCEEDED || response.pages().isEmpty()
                || response.pages().stream().anyMatch(page -> page.text().isBlank())) {
            ParseReport.OcrReport report = new ParseReport.OcrReport(true, response.status(), response.engine(),
                    response.engineVersion(), reason, response.pages().stream().map(OcrPageResult::page).toList(),
                    request.artifactId(), OcrAuditState.BLOCKED);
            return reportOnly(request, nativeResult, started, ParseStatus.BLOCKED,
                    List.of(), List.of(response.error() == null ? "OCR_FAILED" : response.error()), report);
        }
        String ocrText = response.pages().stream().sorted(java.util.Comparator.comparing(OcrPageResult::page))
                .map(OcrPageResult::text).reduce("", (left, right) -> left + right + "\n");
        ParseReport.OcrReport report = new ParseReport.OcrReport(true, OcrStatus.SUCCEEDED, response.engine(),
                response.engineVersion(), reason, response.pages().stream().map(OcrPageResult::page).toList(),
                request.artifactId(), OcrAuditState.COMPLETED);
        return success(request, ocrText, nativeResult, started, report);
    }

    private ParsedDocument ocrUnavailable(ParseRequest request, NativeResult nativeResult, Instant started,
                                          OcrTriggerReason reason, String error) {
        ParseReport.OcrReport report = new ParseReport.OcrReport(true, OcrStatus.UNAVAILABLE, null, null, reason,
                pages(nativeResult.pageCount()), request.artifactId(), OcrAuditState.BLOCKED);
        return reportOnly(request, nativeResult, started, ParseStatus.OCR_UNAVAILABLE,
                List.of(), List.of(error), report);
    }

    private ParsedDocument success(ParseRequest request, String text, NativeResult nativeResult, Instant started,
                                   ParseReport.OcrReport ocr) {
        String normalized = text.strip();
        if (normalized.isBlank()) {
            return reportOnly(request, nativeResult, started, ParseStatus.BLOCKED,
                    List.of(), List.of("EMPTY_TEXT"), ocr);
        }
        List<String> warnings = new ArrayList<>(nativeResult.warnings());
        if (normalized.indexOf('\uFFFD') >= 0) {
            warnings.add("TEXT_REPLACEMENT_CHARACTER");
        }
        int ocrPages = ocr.status() == OcrStatus.SUCCEEDED ? ocr.pages().size() : 0;
        ParseReport report = new ParseReport(UuidV7.random(), request.spaceId(), request.documentRevisionId(),
                request.artifactId(), request.version(), ParseStatus.SUCCEEDED, request.mediaType(),
                nativeResult.pageCount(), normalized.codePointCount(0, normalized.length()), tokenCount(normalized),
                nativeResult.nativePageCount(), ocrPages, PARSER_NAME, request.parserVersion(),
                elapsed(started), warnings, List.of(), UuidV7.random(), ocr, Instant.now());
        return new ParsedDocument(report, normalized);
    }

    private ParsedDocument reportOnly(ParseRequest request, NativeResult nativeResult, Instant started,
                                      ParseStatus status, List<String> warnings, List<String> errors,
                                      ParseReport.OcrReport ocr) {
        ParseReport report = new ParseReport(UuidV7.random(), request.spaceId(), request.documentRevisionId(),
                request.artifactId(), request.version(), status, request.mediaType(), nativeResult.pageCount(),
                0, 0, nativeResult.nativePageCount(), 0, PARSER_NAME, request.parserVersion(), elapsed(started),
                warnings, errors, null, ocr, Instant.now());
        return new ParsedDocument(report, "");
    }

    private ParsedDocument failure(ParseRequest request, Instant started, String error) {
        ParseReport report = new ParseReport(UuidV7.random(), request.spaceId(), request.documentRevisionId(),
                request.artifactId(), request.version(), ParseStatus.FAILED, request.mediaType(), 0, 0, 0, 0, 0,
                PARSER_NAME, request.parserVersion(), elapsed(started), List.of(), List.of(error), null,
                ParseReport.OcrReport.notRequested(request.artifactId()), Instant.now());
        return new ParsedDocument(report, "");
    }

    private NativeResult text(ParseRequest request, boolean markdown) {
        try {
            String value = decodeUtf8(request.content());
            List<String> warnings = markdown ? markdownWarnings(value) : List.of();
            return new NativeResult(value, 1, 1, warnings, List.of());
        } catch (CharacterCodingException exception) {
            throw new ParserException("INVALID_UTF8", exception);
        }
    }

    private NativeResult pdf(ParseRequest request) throws IOException {
        try (PDDocument document = PDDocument.load(request.content())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            int pageCount = document.getNumberOfPages();
            int nativePageCount = text.isBlank() ? 0 : pageCount;
            return new NativeResult(text, pageCount, nativePageCount, List.of(), List.of());
        }
    }

    private NativeResult docx(ParseRequest request) throws IOException {
        StringBuilder text = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(request.content()))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                text.append(paragraph.getText()).append('\n');
            }
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    row.getTableCells().forEach(cell -> text.append(cell.getText()).append('\t'));
                    text.append('\n');
                }
            }
        }
        return new NativeResult(text.toString(), 1, 1, List.of(), List.of());
    }

    private NativeResult pptx(ParseRequest request) throws IOException {
        StringBuilder text = new StringBuilder();
        int slides;
        try (XMLSlideShow presentation = new XMLSlideShow(new ByteArrayInputStream(request.content()))) {
            slides = presentation.getSlides().size();
            for (XSLFSlide slide : presentation.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        text.append(textShape.getText()).append('\n');
                    }
                }
            }
        }
        return new NativeResult(text.toString(), slides, slides, List.of(), List.of());
    }

    private NativeResult xlsx(ParseRequest request) throws IOException {
        StringBuilder text = new StringBuilder();
        int sheets;
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(request.content()))) {
            sheets = workbook.getNumberOfSheets();
            for (int sheetIndex = 0; sheetIndex < sheets; sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                text.append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    row.forEach(cell -> text.append(formatter.formatCellValue(cell)).append('\t'));
                    text.append('\n');
                }
            }
        }
        return new NativeResult(text.toString(), sheets, sheets, List.of(), List.of());
    }

    private static String decodeUtf8(byte[] content) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content));
        return decoded.toString();
    }

    private static List<String> markdownWarnings(String text) {
        List<String> warnings = new ArrayList<>();
        if (!text.startsWith("---")) warnings.add("NO_FRONTMATTER");
        if (!text.contains("#")) warnings.add("NO_HEADING");
        return warnings;
    }

    private static List<Integer> pages(int count) {
        List<Integer> pages = new ArrayList<>();
        for (int page = 1; page <= count; page++) pages.add(page);
        return pages;
    }

    private static long tokenCount(String text) {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    private static long elapsed(Instant started) {
        return Math.max(0, Duration.between(started, Instant.now()).toMillis());
    }

    private record NativeResult(String text, int pageCount, int nativePageCount,
                                List<String> warnings, List<Integer> ocrPages) { }
}
