package com.ragforge.ingestion.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Bounded adapter for a locally installed Tesseract executable.
 *
 * <p>The worker owns the PDF rendering boundary and invokes one isolated
 * process per page. The adapter never logs document content, source paths, or
 * command output; failures are reduced to stable classifications for the
 * parser's audit report.</p>
 */
public final class TesseractOcrEngine implements OcrEngine {
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("[A-Za-z0-9_]+(?:\\+[A-Za-z0-9_]+)*");
    private static final int DEFAULT_DPI = 200;
    private static final int DEFAULT_MAX_PAGES = 20;
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 200_000;
    private static final int DEFAULT_MAX_CONTENT_BYTES = 25 * 1024 * 1024;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String executable;
    private final String language;
    private final Duration timeout;
    private final int dpi;
    private final int maxPages;
    private final int maxOutputChars;
    private final int maxContentBytes;
    private final String engineVersion;

    public TesseractOcrEngine(Path executable) {
        this(executable, "eng", DEFAULT_TIMEOUT, DEFAULT_DPI, DEFAULT_MAX_PAGES,
                DEFAULT_MAX_OUTPUT_CHARS, DEFAULT_MAX_CONTENT_BYTES);
    }

    TesseractOcrEngine(Path executable, String language, Duration timeout, int dpi, int maxPages,
                       int maxOutputChars, int maxContentBytes) {
        this.executable = validateExecutable(executable);
        if (language == null || !LANGUAGE_PATTERN.matcher(language).matches()) {
            throw new IllegalArgumentException("OCR language is invalid");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative() || dpi < 72 || dpi > 600
                || maxPages < 1 || maxOutputChars < 1 || maxContentBytes < 1) {
            throw new IllegalArgumentException("OCR runtime limits are invalid");
        }
        this.language = language;
        this.timeout = timeout;
        this.dpi = dpi;
        this.maxPages = maxPages;
        this.maxOutputChars = maxOutputChars;
        this.maxContentBytes = maxContentBytes;
        this.engineVersion = probeVersion();
    }

    /** Resolves an explicit property, environment variable, or safe local default. */
    public static Path resolveExecutable() {
        String configured = System.getProperty("ragforge.tesseract.executable");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("TESSERACT_EXECUTABLE");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        Path windowsInstall = Path.of("C:\\Program Files\\Tesseract-OCR\\tesseract.exe");
        if (Files.isRegularFile(windowsInstall)) {
            return windowsInstall;
        }
        Path x86WindowsInstall = Path.of("C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe");
        if (Files.isRegularFile(x86WindowsInstall)) {
            return x86WindowsInstall;
        }
        return Path.of("tesseract");
    }

    @Override
    public OcrResponse recognize(OcrRequest request) throws OcrException {
        if (!"application/pdf".equalsIgnoreCase(request.mediaType())) {
            throw new OcrException("OCR_UNSUPPORTED_MEDIA_TYPE");
        }
        byte[] content = request.content();
        if (content.length > maxContentBytes) {
            throw new OcrException("OCR_INPUT_TOO_LARGE");
        }
        if (request.pages().isEmpty() || request.pages().size() > maxPages) {
            throw new OcrException("OCR_PAGE_LIMIT_EXCEEDED");
        }
        Set<Integer> uniquePages = new HashSet<>(request.pages());
        if (uniquePages.size() != request.pages().size() || request.pages().stream().anyMatch(page -> page < 1)) {
            throw new OcrException("OCR_PAGE_SELECTION_INVALID");
        }

        Path temporaryDirectory = null;
        try (PDDocument document = PDDocument.load(content)) {
            if (document.getNumberOfPages() > maxPages
                    || request.pages().stream().anyMatch(page -> page > document.getNumberOfPages())) {
                throw new OcrException("OCR_PAGE_LIMIT_EXCEEDED");
            }
            temporaryDirectory = Files.createTempDirectory("ragforge-ocr-");
            PDFRenderer renderer = new PDFRenderer(document);
            List<OcrPageResult> results = new ArrayList<>();
            for (int page : request.pages()) {
                BufferedImage image = renderer.renderImageWithDPI(page - 1, dpi, ImageType.RGB);
                Path imagePath = temporaryDirectory.resolve(String.format(Locale.ROOT, "page-%03d.png", page));
                if (!ImageIO.write(image, "png", imagePath.toFile())) {
                    throw new OcrException("OCR_RENDER_FAILED");
                }
                String text = runTesseract(imagePath);
                if (text.isBlank()) {
                    throw new OcrException("OCR_EMPTY_PAGE");
                }
                results.add(new OcrPageResult(page, text));
            }
            return new OcrResponse(OcrStatus.SUCCEEDED, "tesseract", engineVersion, results, null);
        } catch (OcrException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new OcrException("OCR_RENDER_FAILED", exception);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    private String runTesseract(Path imagePath) {
        return execute(List.of(executable, imagePath.toString(), "stdout", "--psm", "6", "-l", language,
                "--loglevel", "ERROR"), timeout, maxOutputChars);
    }

    private String probeVersion() {
        String output = execute(List.of(executable, "--version"), timeout, 2_000);
        String firstLine = output.lines().findFirst().orElse("").strip();
        if (firstLine.isBlank()) {
            throw new OcrException("OCR_RUNTIME_VERSION_UNAVAILABLE");
        }
        return firstLine.length() > 128 ? firstLine.substring(0, 128) : firstLine;
    }

    private String execute(List<String> command, Duration processTimeout, int outputLimit) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException exception) {
            throw new OcrException("OCR_RUNTIME_UNAVAILABLE", exception);
        }

        ExecutorService readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ragforge-ocr-output-reader");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> output = readerExecutor.submit(() -> readLimited(process.getInputStream(), outputLimit));
        try {
            if (!process.waitFor(processTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new OcrException("OCR_TIMEOUT");
            }
            String result = output.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new OcrException("OCR_PROCESS_FAILED");
            }
            return result.replace("\r\n", "\n").strip();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new OcrException("OCR_INTERRUPTED", exception);
        } catch (ExecutionException exception) {
            process.destroyForcibly();
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new OcrException(cause instanceof OcrException ? cause.getMessage() : "OCR_OUTPUT_FAILED", cause);
        } catch (TimeoutException exception) {
            process.destroyForcibly();
            throw new OcrException("OCR_OUTPUT_TIMEOUT", exception);
        } finally {
            readerExecutor.shutdownNow();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readLimited(InputStream input, int outputLimit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > outputLimit) {
                throw new OcrException("OCR_OUTPUT_LIMIT_EXCEEDED");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String validateExecutable(Path executable) {
        if (executable == null || executable.toString().isBlank() || executable.toString().contains("\u0000")) {
            throw new IllegalArgumentException("OCR executable is invalid");
        }
        if (executable.isAbsolute() && (!Files.isRegularFile(executable) || !Files.isExecutable(executable))) {
            throw new OcrException("OCR_RUNTIME_UNAVAILABLE");
        }
        if (!executable.isAbsolute() && executable.getNameCount() > 1
                && (!Files.isRegularFile(executable) || !Files.isExecutable(executable))) {
            throw new OcrException("OCR_RUNTIME_UNAVAILABLE");
        }
        return executable.toString();
    }

    private static void deleteTemporaryDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary OCR files contain only synthetic page images and are best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Do not expose local temporary paths through the parser error contract.
        }
    }
}
