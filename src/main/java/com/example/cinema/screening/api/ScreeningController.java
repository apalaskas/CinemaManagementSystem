package com.example.cinema.screening.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.cinema.common.api.EntityTagParser;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.screening.service.ScreeningCommandResult;
import com.example.cinema.screening.service.ScreeningAssignmentReviewService;
import com.example.cinema.screening.service.ScreeningFinalizationService;
import com.example.cinema.screening.service.ScreeningPreparationService;
import com.example.cinema.screening.service.ScreeningSubmissionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ScreeningController {

    private final ScreeningPreparationService service;
    private final ScreeningSubmissionService submissionService;
    private final ScreeningAssignmentReviewService assignmentReviewService;
    private final ScreeningFinalizationService finalizationService;
    private final EntityTagParser entityTagParser;

    public ScreeningController(
            ScreeningPreparationService service,
            ScreeningSubmissionService submissionService,
            ScreeningAssignmentReviewService assignmentReviewService,
            ScreeningFinalizationService finalizationService,
            EntityTagParser entityTagParser) {
        this.service = service;
        this.submissionService = submissionService;
        this.assignmentReviewService = assignmentReviewService;
        this.finalizationService = finalizationService;
        this.entityTagParser = entityTagParser;
    }

    @PostMapping(path = "/programs/{programId}/screenings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreeningDetailResponse> create(
            @PathVariable UUID programId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ScreeningCreateRequest request) {
        ScreeningCommandResult<ScreeningDetailResponse> result =
                service.create(programId, request, idempotencyKey);
        return ResponseEntity.status(result.status())
                .location(URI.create("/api/v1/screenings/" + result.body().screeningId()))
                .eTag(entityTagParser.format(result.body().version()))
                .body(result.body());
    }

    @PatchMapping(path = "/screenings/{screeningId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreeningDetailResponse> update(
            @PathVariable UUID screeningId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ScreeningUpdateRequest request) {
        ScreeningCommandResult<ScreeningDetailResponse> result = service.update(
                screeningId,
                entityTagParser.parseVersion(ifMatch),
                request,
                idempotencyKey);
        return ResponseEntity.status(result.status())
                .eTag(entityTagParser.format(result.body().version()))
                .body(result.body());
    }

    @DeleteMapping("/screenings/{screeningId}")
    public ResponseEntity<Void> withdraw(
            @PathVariable UUID screeningId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
        service.withdraw(screeningId, entityTagParser.parseVersion(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/screenings/{screeningId}/submit")
    public ResponseEntity<ScreeningDetailResponse> submit(
            @PathVariable UUID screeningId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) byte[] requestBody) {
        if (requestBody != null && requestBody.length > 0) {
            throw new InvalidInputException(
                    "UNEXPECTED_REQUEST_BODY", "Screening submission does not accept a request body.");
        }
        ScreeningCommandResult<ScreeningDetailResponse> result = submissionService.submit(
                screeningId,
                entityTagParser.parseVersion(ifMatch),
                idempotencyKey);
        return ResponseEntity.status(result.status())
                .eTag(entityTagParser.format(result.body().version()))
                .body(result.body());
    }

    @PostMapping(path = "/screenings/{screeningId}/handler", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreeningHandlerAssignmentResponse> assignHandler(
            @PathVariable UUID screeningId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ScreeningHandlerAssignmentRequest request) {
        ScreeningCommandResult<ScreeningHandlerAssignmentResponse> result =
                assignmentReviewService.assignHandler(
                        screeningId,
                        entityTagParser.parseVersion(ifMatch),
                        request,
                        idempotencyKey);
        return ResponseEntity.status(result.status())
                .eTag(entityTagParser.format(result.body().version()))
                .body(result.body());
    }

    @PostMapping(path = "/screenings/{screeningId}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreeningReviewResponse> submitReview(
            @PathVariable UUID screeningId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ScreeningReviewRequest request) {
        ScreeningCommandResult<ScreeningReviewResponse> result = assignmentReviewService.submitReview(
                screeningId,
                entityTagParser.parseVersion(ifMatch),
                request,
                idempotencyKey);
        return ResponseEntity.status(result.status())
                .eTag(entityTagParser.format(result.body().screeningVersion()))
                .body(result.body());
    }

    @PostMapping(path = "/screenings/{screeningId}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreeningDecisionResponse> decide(
            @PathVariable UUID screeningId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ScreeningDecisionRequest request) {
        ScreeningCommandResult<ScreeningDecisionResponse> result = finalizationService.decide(
                screeningId,
                entityTagParser.parseVersion(ifMatch),
                request,
                idempotencyKey);
        return ResponseEntity.status(result.status())
                .eTag(entityTagParser.format(result.body().version()))
                .body(result.body());
    }

    @PostMapping(path = "/screenings/{screeningId}/final-submission", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreeningDetailResponse> finalSubmit(
            @PathVariable UUID screeningId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ScreeningFinalSubmissionRequest request) {
        ScreeningCommandResult<ScreeningDetailResponse> result = finalizationService.finalSubmit(
                screeningId,
                entityTagParser.parseVersion(ifMatch),
                request,
                idempotencyKey);
        return ResponseEntity.status(result.status())
                .eTag(entityTagParser.format(result.body().version()))
                .body(result.body());
    }

    @PostMapping(path = "/screenings/{screeningId}/schedule", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreeningScheduleResponse> schedule(
            @PathVariable UUID screeningId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ScreeningScheduleRequest request) {
        ScreeningCommandResult<ScreeningScheduleResponse> result = finalizationService.schedule(
                screeningId,
                entityTagParser.parseVersion(ifMatch),
                request,
                idempotencyKey);
        return ResponseEntity.status(result.status())
                .eTag(entityTagParser.format(result.body().version()))
                .body(result.body());
    }
}
