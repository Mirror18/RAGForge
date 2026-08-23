package com.ragforge.server.ingestion;

import com.ragforge.server.identity.SessionPrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
public class BusinessIngestionController {
    private final BusinessIngestionService service;
    private final WebSourceIngestionService webSources;

    public BusinessIngestionController(BusinessIngestionService service, WebSourceIngestionService webSources) {
        this.service = service;
        this.webSources = webSources;
    }

    @PostMapping(value = "/sources/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BusinessIngestionService.UploadView upload(@PathVariable UUID spaceId,
                                                       @RequestPart("file") MultipartFile file,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                       @AuthenticationPrincipal SessionPrincipal principal,
                                                       HttpServletRequest request) {
        return service.upload(spaceId, file, idempotencyKey, principal, request);
    }

    @PostMapping("/sources/web")
    public BusinessIngestionService.UploadView web(@PathVariable UUID spaceId,
                                                   @RequestBody WebSourceIngestionService.WebSourceRequest source,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                   @AuthenticationPrincipal SessionPrincipal principal,
                                                   HttpServletRequest request) {
        return webSources.ingest(spaceId, source, idempotencyKey, principal, request);
    }

    @GetMapping("/source-documents")
    public List<IngestionRepository.SourceDocument> documents(@PathVariable UUID spaceId,
                                                              @AuthenticationPrincipal SessionPrincipal principal) {
        return service.documents(spaceId, principal);
    }

    @GetMapping("/ingestion-jobs")
    public List<BusinessIngestionService.JobView> jobs(@PathVariable UUID spaceId,
                                                       @AuthenticationPrincipal SessionPrincipal principal) {
        return service.jobs(spaceId, principal);
    }

    @GetMapping("/ingestion-jobs/{jobId}")
    public BusinessIngestionService.JobView job(@PathVariable UUID spaceId, @PathVariable UUID jobId,
                                                @AuthenticationPrincipal SessionPrincipal principal) {
        return service.job(spaceId, jobId, principal);
    }

    @GetMapping("/document-revisions/{revisionId}/parse-report")
    public BusinessIngestionService.ParseReportView parseReport(@PathVariable UUID spaceId,
                                                                 @PathVariable UUID revisionId,
                                                                 @AuthenticationPrincipal SessionPrincipal principal) {
        return service.parseReport(spaceId, revisionId, principal);
    }
}
