package com.example.cinema.program.api;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;

import com.example.cinema.common.api.PageResponse;
import com.example.cinema.common.api.EntityTagParser;
import com.example.cinema.program.service.ProgramCommandResult;
import com.example.cinema.program.service.ProgramLifecycleService;
import com.example.cinema.program.service.ProgramManagementService;
import com.example.cinema.search.visibility.SearchAndVisibilityService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping(path = "/api/v1/programs", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class ProgramController {

    private final ProgramManagementService service;
    private final ProgramLifecycleService lifecycleService;
    private final SearchAndVisibilityService searchAndVisibilityService;
    private final EntityTagParser entityTagParser;

    public ProgramController(
            ProgramManagementService service,
            ProgramLifecycleService lifecycleService,
            SearchAndVisibilityService searchAndVisibilityService,
            EntityTagParser entityTagParser) {
        this.service = service;
        this.lifecycleService = lifecycleService;
        this.searchAndVisibilityService = searchAndVisibilityService;
        this.entityTagParser = entityTagParser;
    }

    @GetMapping
    public PageResponse<ProgramViewResponse> search(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String filmTitle,
            @RequestParam(required = false) String auditorium,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false) @Min(1) Integer size) {
        return searchAndVisibilityService.searchPrograms(new ProgramSearchParameters(
                name, description, fromDate, toDate, filmTitle, auditorium,
                direction, page, size));
    }

    @GetMapping("/{programId}")
    public ProgramViewResponse view(@PathVariable UUID programId) {
        return searchAndVisibilityService.viewProgram(programId);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProgramDetailResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ProgramCreateRequest request) {
        ProgramCommandResult<ProgramDetailResponse> result = service.create(request, idempotencyKey);
        ProgramDetailResponse body = result.body();
        return ResponseEntity.status(result.status())
                .location(URI.create("/api/v1/programs/" + body.programId()))
                .eTag(entityTagParser.format(body.version()))
                .body(body);
    }

    @PatchMapping(path = "/{programId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProgramDetailResponse> update(
            @PathVariable UUID programId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ProgramUpdateRequest request) {
        long expectedVersion = entityTagParser.parseVersion(ifMatch);
        ProgramCommandResult<ProgramDetailResponse> result =
                service.update(programId, expectedVersion, request, idempotencyKey);
        return ResponseEntity.status(result.status())
                .eTag(entityTagParser.format(result.body().version()))
                .body(result.body());
    }

    @DeleteMapping("/{programId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID programId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
        service.delete(programId, entityTagParser.parseVersion(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{programId}/roles", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProgramRoleResponse> addRole(
            @PathVariable UUID programId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ProgramRoleRequest request) {
        ProgramCommandResult<ProgramRoleResponse> result = service.addRole(
                programId,
                entityTagParser.parseVersion(ifMatch),
                request,
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.valueOf(result.status()))
                .location(URI.create("/api/v1/programs/" + programId + "/roles/" + result.body().userId()))
                .eTag(entityTagParser.format(result.body().programVersion()))
                .body(result.body());
    }

    @DeleteMapping("/{programId}/roles/{userId}")
    public ResponseEntity<Void> removeRole(
            @PathVariable UUID programId,
            @PathVariable UUID userId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
        long newVersion = service.removeRole(programId, userId, entityTagParser.parseVersion(ifMatch));
        return ResponseEntity.noContent()
                .eTag(entityTagParser.format(newVersion))
                .build();
    }

    @PostMapping(path = "/{programId}/transitions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProgramTransitionResponse> transition(
            @PathVariable UUID programId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ProgramTransitionRequest request) {
        ProgramCommandResult<ProgramTransitionResponse> result = lifecycleService.transition(
                programId,
                entityTagParser.parseVersion(ifMatch),
                request,
                idempotencyKey);
        return ResponseEntity.status(result.status())
                .eTag(entityTagParser.format(result.body().version()))
                .body(result.body());
    }
}
