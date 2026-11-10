package app.brewflow.staff;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StaffRepository {

    private static final String COLUMNS = """
            id, login, display_name, role, credential_hash, active, must_change_credential, failed_attempts,
            (locked_until is not null and locked_until > now()) as locked, session_epoch, revision, created_at
            """;

    private final JdbcClient jdbc;

    public StaffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StaffUser> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from staff_user where id = :id")
                .param("id", id).query(StaffRepository::map).optional();
    }

    public Optional<StaffUser> findByIdForUpdate(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from staff_user where id = :id for update")
                .param("id", id).query(StaffRepository::map).optional();
    }

    public Optional<StaffUser> findByLoginForUpdate(String normalizedLogin) {
        return jdbc.sql("select " + COLUMNS + " from staff_user where login_normalized = :login for update")
                .param("login", normalizedLogin).query(StaffRepository::map).optional();
    }

    public List<StaffUser> findAll() {
        return jdbc.sql("select " + COLUMNS + " from staff_user order by role, login_normalized")
                .query(StaffRepository::map).list();
    }

    /** Locks every active admin row in id order, so concurrent demotions cannot remove the last one or deadlock. */
    public int lockActiveAdmins() {
        return jdbc.sql("select id from staff_user where role = 'ADMIN' and active order by id for update")
                .query((rs, n) -> rs.getObject(1, UUID.class)).list().size();
    }

    public boolean adminExists() {
        return jdbc.sql("select exists (select 1 from staff_user where role = 'ADMIN')")
                .query(Boolean.class).single();
    }

    public boolean loginExists(String normalizedLogin) {
        return jdbc.sql("select exists (select 1 from staff_user where login_normalized = :login)")
                .param("login", normalizedLogin).query(Boolean.class).single();
    }

    public void insert(UUID id, String login, String displayName, Role role, String credentialHash, OffsetDateTime now) {
        jdbc.sql("""
                        insert into staff_user (id, login, login_normalized, display_name, role, credential_hash,
                                                active, must_change_credential, created_at)
                        values (:id, :login, :normalized, :displayName, :role, :hash, true, true, :now)
                        """)
                .param("id", id)
                .param("login", login.trim())
                .param("normalized", StaffUser.normalizeLogin(login))
                .param("displayName", displayName.trim())
                .param("role", role.name())
                .param("hash", credentialHash)
                .param("now", now)
                .update();
    }

    public int recordFailedAttempt(UUID id) {
        return jdbc.sql("update staff_user set failed_attempts = failed_attempts + 1 where id = :id returning failed_attempts")
                .param("id", id).query(Integer.class).single();
    }

    /** Lockout has no expiry: the audited credential reset is the only unlock path (section 2.1). */
    public void lock(UUID id) {
        jdbc.sql("update staff_user set locked_until = 'infinity' where id = :id").param("id", id).update();
    }

    public void clearFailedAttempts(UUID id) {
        jdbc.sql("update staff_user set failed_attempts = 0 where id = :id and failed_attempts <> 0").param("id", id).update();
    }

    /** Own credential change: clears the first-login flag and ends every other session. Returns the new epoch. */
    public int changeOwnCredential(UUID id, String hash) {
        return jdbc.sql("""
                        update staff_user
                        set credential_hash = :hash, must_change_credential = false, failed_attempts = 0,
                            session_epoch = session_epoch + 1, revision = revision + 1
                        where id = :id
                        returning session_epoch
                        """)
                .param("id", id).param("hash", hash).query(Integer.class).single();
    }

    /** Admin reset: temporary credential, unlock, forced change, sessions ended. */
    public void resetCredential(UUID id, String hash) {
        jdbc.sql("""
                        update staff_user
                        set credential_hash = :hash, must_change_credential = true, failed_attempts = 0, locked_until = null,
                            session_epoch = session_epoch + 1, revision = revision + 1
                        where id = :id
                        """)
                .param("id", id).param("hash", hash).update();
    }

    public void update(UUID id, String displayName, Role role, boolean active, boolean endSessions) {
        jdbc.sql("""
                        update staff_user
                        set display_name = :displayName, role = :role, active = :active,
                            session_epoch = session_epoch + case when :endSessions then 1 else 0 end,
                            revision = revision + 1
                        where id = :id
                        """)
                .param("id", id)
                .param("displayName", displayName)
                .param("role", role.name())
                .param("active", active)
                .param("endSessions", endSessions)
                .update();
    }

    private static StaffUser map(ResultSet rs, int rowNum) throws SQLException {
        return new StaffUser(
                rs.getObject("id", UUID.class),
                rs.getString("login"),
                rs.getString("display_name"),
                Role.valueOf(rs.getString("role")),
                rs.getString("credential_hash"),
                rs.getBoolean("active"),
                rs.getBoolean("must_change_credential"),
                rs.getInt("failed_attempts"),
                rs.getBoolean("locked"),
                rs.getInt("session_epoch"),
                rs.getInt("revision"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
