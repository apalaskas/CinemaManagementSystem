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
import com.example.cinema.screening.service.ScreeningCommandResult;
import com.example.cinema.screening.service.ScreeningPreparationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ScreeningController {

    private final ScreeningPreparationService service;
    private final EntityTagParser entityTagParser;

    public ScreeningController(ScreeningPreparationService service, EntityTagParser entityTagParser) {
        this.service = service;
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
}
