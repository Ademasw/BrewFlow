package app.brewflow.bootstrap;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Optional;
import java.util.Set;

/**
 * Section 2.1: the bootstrap password file must not be readable by other operating-system users.
 * POSIX: no group or other read bit. Windows ACL: only the owner and SYSTEM may read
 * (lock it down with {@code icacls file /inheritance:r /grant:r "%USERNAME%:R"}).
 */
final class PasswordFileCheck {

    private PasswordFileCheck() {
    }

    static boolean readableByOthers(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            Set<PosixFilePermission> perms = posix.readAttributes().permissions();
            return perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ);
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) {
            // unknown permission model: fail closed
            return true;
        }
        UserPrincipal owner = acl.getOwner();
        Optional<UserPrincipal> system = lookup("SYSTEM");
        for (AclEntry entry : acl.getAcl()) {
            if (entry.type() != AclEntryType.ALLOW || !entry.permissions().contains(AclEntryPermission.READ_DATA)) {
                continue;
            }
            UserPrincipal who = entry.principal();
            if (!who.equals(owner) && !system.map(who::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<UserPrincipal> lookup(String name) {
        try {
            return Optional.of(FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(name));
        } catch (IOException | UnsupportedOperationException e) {
            return Optional.empty();
        }
    }
}
