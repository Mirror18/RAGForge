package com.ragforge.server.ingestion;

import com.ragforge.server.identity.SessionPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final GitSourceService gitSources;

    public BusinessIngestionController(BusinessIngestionService service, WebSourceIngestionService webSources, GitSourceService gitSources) {
        this.service = service;
        this.webSources = webSources;
        this.gitSources = gitSources;
    }

    @GetMapping("/sources")
    public List<GitSourceService.SourceView> sources(@PathVariable UUID spaceId, @AuthenticationPrincipal SessionPrincipal principal) {
        return gitSources.list(spaceId, principal);
    }

    @PostMapping("/sources/git")
    public GitSourceService.SourceView configureGit(@PathVariable UUID spaceId,
                                                     @RequestBody GitSourceService.GitSourceRequest source,
                                                     @AuthenticationPrincipal SessionPrincipal principal,
                                                     HttpServletRequest request) {
        return gitSources.configure(spaceId, source, principal, request);
    }

    /** Contract-shaped alias used by the public source API. */
    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public GitSourceService.SourceView createSource(@PathVariable UUID spaceId,
                                                    @RequestBody GitSourceService.GitSourceRequest source,
                                                    @AuthenticationPrincipal SessionPrincipal principal,
                                                    HttpServletRequest request) {
        return gitSources.configure(spaceId, source, principal, request);
    }

    @GetMapping("/sources/{sourceId}")
    public GitSourceService.SourceView source(@PathVariable UUID spaceId, @PathVariable UUID sourceId,
                                              @AuthenticationPrincipal SessionPrincipal principal) {
        return gitSources.get(spaceId, sourceId, principal);
    }

    @PostMapping("/sources/{sourceId}/sync")
    public GitSourceService.SyncCommand syncGit(@PathVariable UUID spaceId, @PathVariable UUID sourceId,
                                                 @RequestParam(defaultValue = "INCREMENTAL") String mode,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                 @AuthenticationPrincipal SessionPrincipal principal,
                                                 HttpServletRequest request) {
        return gitSources.synchronize(spaceId, sourceId, mode, idempotencyKey, principal, request);
    }

    @PostMapping("/sources/{sourceId}/sync-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GitSourceService.SyncCommand syncJob(@PathVariable UUID spaceId, @PathVariable UUID sourceId,
                                                @RequestBody GitSourceService.SyncRequest body,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                @AuthenticationPrincipal SessionPrincipal principal,
                                                HttpServletRequest request) {
        return gitSources.synchronize(spaceId, sourceId, body == null ? "INCREMENTAL_SYNC" : body.mode(), idempotencyKey, principal, request);
    }

    @GetMapping("/sync-jobs")
    public List<BusinessIngestionService.JobView> syncJobs(@PathVariable UUID spaceId,
                                                           @AuthenticationPrincipal SessionPrincipal principal) {
        return service.jobs(spaceId, principal).stream().filter(value -> value.job().documentRevisionId() == null).toList();
    }

    @GetMapping("/sync-jobs/{jobId}")
    public BusinessIngestionService.JobView syncJob(@PathVariable UUID spaceId, @PathVariable UUID jobId,
                                                    @AuthenticationPrincipal SessionPrincipal principal) {
        return service.job(spaceId, jobId, principal);
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
