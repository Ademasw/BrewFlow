package app.brewflow.staff;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import app.brewflow.security.CurrentUser;
import app.brewflow.staff.StaffService.CreateStaff;
import app.brewflow.staff.StaffService.UpdateStaff;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin: staff", description = "Staff accounts (section 2.1)")
@RequestMapping("/api/v1/admin/staff")
public class StaffAdminController {

    public record CreateRequest(@NotBlank @Size(max = 64) String login,
                                @NotBlank @Size(max = 120) String displayName,
                                @NotNull Role role,
                                @NotBlank @Size(max = 128) String temporaryCredential) {
    }

    public record PatchRequest(@Size(min = 1, max = 120) String displayName, Role role, Boolean active,
                               @NotNull Integer expectedRevision) {
    }

    public record ResetRequest(@NotBlank @Size(max = 128) String temporaryCredential) {
    }

    private final StaffService service;

    public StaffAdminController(StaffService service) {
        this.service = service;
    }

    @Operation(summary = "List staff without credential hashes")
    @GetMapping
    List<Map<String, Object>> list() {
        return service.list().stream().map(StaffAdminController::view).toList();
    }

    @Operation(summary = "Create staff with a temporary credential (must change at first login)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@Valid @RequestBody CreateRequest body) {
        return view(service.create(CurrentUser.get().id(),
                new CreateStaff(body.login(), body.displayName(), body.role(), body.temporaryCredential())));
    }

    @Operation(summary = "Edit display name, role, active; 409 VERSION_CONFLICT or LAST_ACTIVE_ADMIN")
    @PatchMapping("/{id}")
    Map<String, Object> update(@PathVariable UUID id, @Valid @RequestBody PatchRequest body) {
        return view(service.update(CurrentUser.get().id(), id,
                new UpdateStaff(body.displayName(), body.role(), body.active(), body.expectedRevision())));
    }

    @Operation(summary = "Temporary credential, unlock, end sessions (audited)")
    @PostMapping("/{id}/credential-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@PathVariable UUID id, @Valid @RequestBody ResetRequest body) {
        service.resetCredential(CurrentUser.get().id(), id, body.temporaryCredential());
    }

    /** Never includes the credential hash. */
    static Map<String, Object> view(StaffUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("login", u.login());
        m.put("displayName", u.displayName());
        m.put("role", u.role());
        m.put("active", u.active());
        m.put("mustChangeCredential", u.mustChangeCredential());
        m.put("locked", u.locked());
        m.put("revision", u.revision());
        m.put("createdAt", u.createdAt());
        return m;
    }
}
