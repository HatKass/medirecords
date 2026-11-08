package com.medirecords.patient;

import com.medirecords.common.web.BadRequestException;
import com.medirecords.common.web.PageResponse;
import com.medirecords.patient.PatientDtos.OnUpdate;
import com.medirecords.patient.PatientDtos.PatientFilter;
import com.medirecords.patient.PatientDtos.PatientRequest;
import com.medirecords.patient.PatientDtos.PatientResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Patient CRUD. Reads are open to every signed-in user; writes are ADMIN-only through the
 * URL rules in {@code SecurityConfig} (all non-GET requests under /api).
 */
@RestController
@RequestMapping("/api/patients")
@Tag(name = "Patients")
public class PatientController {

    /** Public sort keys; anything else is rejected instead of failing deep inside JPA. */
    private static final Set<String> SORTABLE = Set.of(
            "firstName", "lastName", "dateOfBirth", "gender", "email", "city", "bloodType", "createdAt", "updatedAt");

    private final PatientService service;

    public PatientController(PatientService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List patients with paging, sorting and optional filters",
            description = "Sort by firstName, lastName, dateOfBirth, gender, email, city, bloodType, createdAt "
                    + "or updatedAt (e.g. sort=lastName,asc). Pages are 0-based; max page size 100.")
    public PageResponse<PatientResponse> list(
            @Parameter(description = "Case-insensitive match anywhere in \"first last\"")
            @RequestParam(required = false) @Size(max = 100) String name,
            @Parameter(description = "Exact city, case-insensitive")
            @RequestParam(required = false) @Size(max = 100) String city,
            @RequestParam(required = false) BloodType bloodType,
            @ParameterObject @PageableDefault(size = 20)
            @SortDefault.SortDefaults({@SortDefault("lastName"), @SortDefault("firstName")}) Pageable pageable) {
        PatientFilter filter = new PatientFilter(PatientNormalizer.text(name), PatientNormalizer.text(city), bloodType);
        return PageResponse.from(service.search(filter, withStableOrder(pageable)));
    }

    @GetMapping("/cities")
    @Operation(summary = "Distinct cities, for filter dropdowns")
    public List<String> cities() {
        return service.cities();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one patient")
    public PatientResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @Operation(summary = "Create a patient (ADMIN)")
    public ResponseEntity<PatientResponse> create(@Valid @RequestBody PatientRequest request) {
        PatientResponse created = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a patient's details (ADMIN). Send the version you read; a stale one gives 409.")
    public PatientResponse update(@PathVariable long id,
            @Validated({Default.class, OnUpdate.class}) @RequestBody PatientRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a patient (ADMIN)")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    /** Validates sort keys and appends id as a tie-breaker so rows never jump between pages. */
    private static Pageable withStableOrder(Pageable pageable) {
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new BadRequestException("Cannot sort by '" + order.getProperty() + "'. Allowed: "
                        + String.join(", ", SORTABLE.stream().sorted().toList()) + ".");
            }
            orders.add(order);
        }
        orders.add(Sort.Order.asc("id"));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }
}
