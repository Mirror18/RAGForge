package com.ragforge.server.ingestion;

import com.ragforge.server.identity.SessionPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final SourceTaskCenterService taskCenter;

    public BusinessIngestionController(BusinessIngestionService service, WebSourceIngestionService webSources,
                                       GitSourceService gitSources, SourceTaskCenterService taskCenter) {
        this.service = service;
        this.webSources = webSources;
        this.gitSources = gitSources;
        this.taskCenter = taskCenter;
    }

    @GetMapping("/sources")
    public CursorPage<GitSourceService.SourceView> sources(@PathVariable UUID spaceId,
                                                           @RequestParam(required = false) String cursor,
                                                           @RequestParam(required = false) Integer limit,
                                                           @RequestParam(required = false) String connectorType,
                                                           @RequestParam(required = false) String sourceState,
                                                           @RequestParam(required = false) String q,
                                                           @AuthenticationPrincipal SessionPrincipal principal) {
        return taskCenter.sources(spaceId, cursor, limit, connectorType, sourceState, q, principal);
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

    @PutMapping("/sources/{sourceId}")
    public GitSourceService.SourceView updateSource(@PathVariable UUID spaceId, @PathVariable UUID sourceId,
                                                    @jakarta.validation.Valid @RequestBody BusinessIngestionService.SourceUpdateRequest request,
                                                    @RequestHeader("If-Match") String ifMatch,
                                                    @AuthenticationPrincipal SessionPrincipal principal,
                                                    HttpServletRequest servletRequest) {
        service.updateSource(spaceId, sourceId, request, ifMatch, principal, servletRequest);
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

    @PostMapping("/sources/{sourceId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SourceTaskCenterService.TaskActionView retrySource(@PathVariable UUID spaceId, @PathVariable UUID sourceId,
                                                               @RequestBody(required = false) SourceTaskCenterService.ActionRequest body,
                                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                               @AuthenticationPrincipal SessionPrincipal principal,
                                                               HttpServletRequest request) {
        return taskCenter.command(spaceId, SourceTaskCenterService.ResourceType.SOURCE, sourceId,
                SourceTaskCenterService.Operation.RETRY, body, key, ifMatch, principal, request);
    }

    @PostMapping("/sources/{sourceId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SourceTaskCenterService.TaskActionView replaySource(@PathVariable UUID spaceId, @PathVariable UUID sourceId,
                                                                @RequestBody(required = false) SourceTaskCenterService.ActionRequest body,
                                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                                @AuthenticationPrincipal SessionPrincipal principal,
                                                                HttpServletRequest request) {
        return taskCenter.command(spaceId, SourceTaskCenterService.ResourceType.SOURCE, sourceId,
                SourceTaskCenterService.Operation.REPLAY, body, key, ifMatch, principal, request);
    }

    @PostMapping("/sources/{sourceId}/resync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SourceTaskCenterService.TaskActionView resyncSource(@PathVariable UUID spaceId, @PathVariable UUID sourceId,
                                                                @RequestBody(required = false) SourceTaskCenterService.ActionRequest body,
                                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                                @AuthenticationPrincipal SessionPrincipal principal,
                                                                HttpServletRequest request) {
        return taskCenter.command(spaceId, SourceTaskCenterService.ResourceType.SOURCE, sourceId,
                SourceTaskCenterService.Operation.RESYNC, body, key, ifMatch, principal, request);
    }

    @PostMapping("/sources/{sourceId}/archive")
    public SourceTaskCenterService.TaskActionView archiveSource(@PathVariable UUID spaceId, @PathVariable UUID sourceId,
                                                                 @RequestBody(required = false) SourceTaskCenterService.ActionRequest body,
                                                                 @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                 @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                                 @AuthenticationPrincipal SessionPrincipal principal,
                                                                 HttpServletRequest request) {
        return taskCenter.command(spaceId, SourceTaskCenterService.ResourceType.SOURCE, sourceId,
                SourceTaskCenterService.Operation.ARCHIVE, body, key, ifMatch, principal, request);
    }

    @DeleteMapping("/sources/{sourceId}")
    public SourceTaskCenterService.TaskActionView deleteSource(@PathVariable UUID spaceId, @PathVariable UUID sourceId,
                                                                @RequestBody(required = false) SourceTaskCenterService.ActionRequest body,
                                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                                @AuthenticationPrincipal SessionPrincipal principal,
                                                                HttpServletRequest request) {
        return taskCenter.command(spaceId, SourceTaskCenterService.ResourceType.SOURCE, sourceId,
                SourceTaskCenterService.Operation.DELETE, body, key, ifMatch, principal, request);
    }

    @GetMapping("/sync-jobs")
    public List<BusinessIngestionService.JobView> syncJobs(@PathVariable UUID spaceId,
                                                           @AuthenticationPrincipal SessionPrincipal principal) {
        return service.jobs(spaceId, principal).stream().filter(value -> value.job().documentRevisionId() == null).toList();
    }

    @GetMapping("/jobs")
    public CursorPage<BusinessIngestionService.JobView> taskJobs(@PathVariable UUID spaceId,
                                                                 @RequestParam(required = false) String cursor,
                                                                 @RequestParam(required = false) Integer limit,
                                                                 @RequestParam(required = false) String status,
                                                                 @RequestParam(required = false) UUID sourceId,
                                                                 @RequestParam(required = false) String q,
                                                                 @AuthenticationPrincipal SessionPrincipal principal) {
        return taskCenter.jobs(spaceId, cursor, limit, status, sourceId, q, principal);
    }

    @GetMapping("/jobs/{jobId}")
    public BusinessIngestionService.JobView taskJob(@PathVariable UUID spaceId, @PathVariable UUID jobId,
                                                    @AuthenticationPrincipal SessionPrincipal principal) {
        return service.job(spaceId, jobId, principal);
    }

    @PostMapping("/jobs/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SourceTaskCenterService.TaskActionView retryJob(@PathVariable UUID spaceId, @PathVariable UUID jobId,
                                                           @RequestBody(required = false) SourceTaskCenterService.ActionRequest body,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                           @AuthenticationPrincipal SessionPrincipal principal,
                                                           HttpServletRequest request) {
        return taskCenter.command(spaceId, SourceTaskCenterService.ResourceType.JOB, jobId,
                SourceTaskCenterService.Operation.RETRY, body, key, ifMatch, principal, request);
    }

    @PostMapping("/jobs/{jobId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SourceTaskCenterService.TaskActionView replayJob(@PathVariable UUID spaceId, @PathVariable UUID jobId,
                                                            @RequestBody(required = false) SourceTaskCenterService.ActionRequest body,
                                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                            @AuthenticationPrincipal SessionPrincipal principal,
                                                            HttpServletRequest request) {
        return taskCenter.command(spaceId, SourceTaskCenterService.ResourceType.JOB, jobId,
                SourceTaskCenterService.Operation.REPLAY, body, key, ifMatch, principal, request);
    }

    @PostMapping("/jobs/{jobId}/resync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SourceTaskCenterService.TaskActionView resyncJob(@PathVariable UUID spaceId, @PathVariable UUID jobId,
                                                            @RequestBody(required = false) SourceTaskCenterService.ActionRequest body,
                                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                            @AuthenticationPrincipal SessionPrincipal principal,
                                                            HttpServletRequest request) {
        return taskCenter.command(spaceId, SourceTaskCenterService.ResourceType.JOB, jobId,
                SourceTaskCenterService.Operation.RESYNC, body, key, ifMatch, principal, request);
    }

    @PostMapping("/jobs/{jobId}/archive")
    public SourceTaskCenterService.TaskActionView archiveJob(@PathVariable UUID spaceId, @PathVariable UUID jobId,
                                                             @RequestBody(required = false) SourceTaskCenterService.ActionRequest body,
                                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                             @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                             @AuthenticationPrincipal SessionPrincipal principal,
                                                             HttpServletRequest request) {
        return taskCenter.command(spaceId, SourceTaskCenterService.ResourceType.JOB, jobId,
                SourceTaskCenterService.Operation.ARCHIVE, body, key, ifMatch, principal, request);
    }

    @DeleteMapping("/jobs/{jobId}")
    public SourceTaskCenterService.TaskActionView deleteJob(@PathVariable UUID spaceId, @PathVariable UUID jobId,
                                                            @RequestBody(required = false) SourceTaskCenterService.ActionRequest body,
                                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                            @AuthenticationPrincipal SessionPrincipal principal,
                                                            HttpServletRequest request) {
        return taskCenter.command(spaceId, SourceTaskCenterService.ResourceType.JOB, jobId,
                SourceTaskCenterService.Operation.DELETE, body, key, ifMatch, principal, request);
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

    @GetMapping("/documents")
    public CursorPage<IngestionRepository.SourceDocument> documentPage(@PathVariable UUID spaceId,
                                                                        @AuthenticationPrincipal SessionPrincipal principal) {
        return service.documentPage(spaceId, principal);
    }

    @GetMapping("/documents/{documentId}")
    public IngestionRepository.SourceDocument document(@PathVariable UUID spaceId, @PathVariable UUID documentId,
                                                       @AuthenticationPrincipal SessionPrincipal principal) {
        return service.document(spaceId, documentId, principal);
    }

    @GetMapping("/documents/{documentId}/revisions")
    public CursorPage<IngestionRepository.DocumentRevision> revisionPage(@PathVariable UUID spaceId,
                                                                          @PathVariable UUID documentId,
                                                                          @AuthenticationPrincipal SessionPrincipal principal) {
        return service.revisionPage(spaceId, documentId, principal);
    }

    @GetMapping("/documents/{documentId}/revisions/{revisionId}")
    public IngestionRepository.DocumentRevision revision(@PathVariable UUID spaceId, @PathVariable UUID documentId,
                                                         @PathVariable UUID revisionId,
                                                         @AuthenticationPrincipal SessionPrincipal principal) {
        return service.revision(spaceId, documentId, revisionId, principal);
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
