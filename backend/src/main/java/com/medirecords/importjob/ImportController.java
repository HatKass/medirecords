package com.medirecords.importjob;

import com.medirecords.common.web.PageResponse;
import com.medirecords.importjob.ImportDtos.ImportErrorResponse;
import com.medirecords.importjob.ImportDtos.ImportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * CSV import. Uploading is ADMIN-only (every non-GET under /api is, see {@code SecurityConfig});
 * history and error reports are readable by every signed-in user.
 */
@RestController
@RequestMapping("/api/imports")
@Tag(name = "Imports")
public class ImportController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ImportService service;

    public ImportController(ImportService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a patients CSV and start an import job (ADMIN)",
            description = "Returns 202 at once; poll GET /api/imports/{id} for progress. Required columns: "
                    + "firstName, lastName, dateOfBirth, gender, email, city; optional: phone, bloodType. "
                    + "Column order and common header spellings (\"First Name\", \"DOB\", \"Sex\"...) do not matter.")
    public ResponseEntity<ImportResponse> upload(@RequestPart("file") MultipartFile file, Authentication authentication) {
        ImportResponse started = service.start(file, authentication.getName());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(started.id()).toUri();
        return ResponseEntity.accepted().location(location).body(started);
    }

    @GetMapping
    @Operation(summary = "Import history, newest first")
    public PageResponse<ImportResponse> list(@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.from(service.list(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One import: status, counts and progress (poll this while it runs)")
    public ImportResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/errors")
    @Operation(summary = "Rejected rows of an import, by row number")
    public PageResponse<ImportErrorResponse> errors(@PathVariable long id,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.from(service.errors(id, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())));
    }

    @GetMapping(value = "/{id}/errors.csv", produces = "text/csv")
    @Operation(summary = "Download the error report as CSV (row, error, then the original columns)")
    public void errorReport(@PathVariable long id, HttpServletResponse response) throws IOException {
        ImportResponse patientImport = service.get(id); // 404 before any header is written
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(ImportService.errorReportFileName(patientImport), StandardCharsets.UTF_8)
                .build().toString());
        service.writeErrorReport(id, response.getWriter());
    }
}
