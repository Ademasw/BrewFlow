package app.brewflow;

import app.brewflow.bootstrap.BootstrapAdminCommand;
import app.brewflow.support.ApiClient;
import app.brewflow.support.IntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** T041: bootstrap-admin creates one admin only when none exists and writes nothing on refusal. */
class BootstrapAdminTest extends IntegrationTest {

    @Autowired BootstrapAdminCommand command;
    @TempDir Path dir;

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void noAdminYet() {
        jdbc.sql("delete from staff_user where role = 'ADMIN'").update();
    }

    @Test
    void createsTheFirstAdminOnceAndForcesACredentialChange() throws IOException {
        Path file = privateFile("owner-first-pass-2026\n");
        int code = command.run(Map.of(BootstrapAdminCommand.ENV_LOGIN, "Owner",
                BootstrapAdminCommand.ENV_PASSWORD_FILE, file.toString()), stderr());
        assertThat(code).isEqualTo(BootstrapAdminCommand.OK);
        assertThat(count("select count(*) from staff_user where role = 'ADMIN'")).isEqualTo(1);
        assertThat(count("select count(*) from audit_event where event_type = 'ADMIN_BOOTSTRAPPED'")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain("owner-first-pass-2026");

        ApiClient owner = loggedIn("owner", "owner-first-pass-2026");
        assertThat(owner.get("/api/v1/admin/staff").code()).isEqualTo("CREDENTIAL_CHANGE_REQUIRED");

        int again = command.run(Map.of(BootstrapAdminCommand.ENV_LOGIN, "someone-else",
                BootstrapAdminCommand.ENV_PASSWORD_FILE, file.toString()), stderr());
        assertThat(again).isEqualTo(BootstrapAdminCommand.ADMIN_EXISTS);
        assertThat(count("select count(*) from staff_user where role = 'ADMIN'")).isEqualTo(1);
        assertThat(count("select count(*) from staff_user where login_normalized = 'someone-else'")).isZero();
    }

    @Test
    void refusesAShortPassword() throws IOException {
        Path file = privateFile("short-pw");
        int code = command.run(Map.of(BootstrapAdminCommand.ENV_LOGIN, "owner",
                BootstrapAdminCommand.ENV_PASSWORD_FILE, file.toString()), stderr());
        assertThat(code).isEqualTo(BootstrapAdminCommand.PASSWORD_TOO_SHORT);
        assertNothingWritten();
    }

    @Test
    void refusesAFileOtherUsersCanRead() throws IOException {
        Path file = sharedFile("a-long-enough-password");
        int code = command.run(Map.of(BootstrapAdminCommand.ENV_LOGIN, "owner",
                BootstrapAdminCommand.ENV_PASSWORD_FILE, file.toString()), stderr());
        assertThat(code).isEqualTo(BootstrapAdminCommand.FILE_READABLE_BY_OTHERS);
        assertNothingWritten();
    }

    @Test
    void refusesMissingConfiguration() {
        assertThat(command.run(Map.of(), stderr())).isEqualTo(BootstrapAdminCommand.BAD_CONFIGURATION);
        assertThat(command.run(Map.of(BootstrapAdminCommand.ENV_LOGIN, "owner",
                BootstrapAdminCommand.ENV_PASSWORD_FILE, dir.resolve("missing.txt").toString()), stderr()))
                .isEqualTo(BootstrapAdminCommand.BAD_CONFIGURATION);
        assertNothingWritten();
    }

    private void assertNothingWritten() {
        assertThat(count("select count(*) from staff_user where role = 'ADMIN'")).isZero();
        assertThat(count("select count(*) from audit_event")).isZero();
    }

    private PrintStream stderr() {
        return new PrintStream(err, true, StandardCharsets.UTF_8);
    }

    private Path privateFile(String content) throws IOException {
        Path p = Files.writeString(dir.resolve("private.txt"), content);
        PosixFileAttributeView posix = Files.getFileAttributeView(p, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(ownerOnly));
        }
        return p;
    }

    private Path sharedFile(String content) throws IOException {
        Path p = Files.writeString(dir.resolve("shared.txt"), content);
        PosixFileAttributeView posix = Files.getFileAttributeView(p, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-r--r--"));
        } else {
            // Windows: add a read grant for a group principal other than the owner
            AclFileAttributeView acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
            var lookup = p.getFileSystem().getUserPrincipalLookupService();
            var others = acl.getAcl().stream().map(AclEntry::principal)
                    .filter(pr -> !pr.equals(ownerOf(acl))).findFirst()
                    .orElse(lookup.lookupPrincipalByName("SYSTEM"));
            List<AclEntry> entries = new java.util.ArrayList<>(acl.getAcl());
            entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(others)
                    .setPermissions(AclEntryPermission.READ_DATA).build());
            acl.setAcl(entries);
        }
        return p;
    }

    private static java.nio.file.attribute.UserPrincipal ownerOf(AclFileAttributeView acl) {
        try {
            return acl.getOwner();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
