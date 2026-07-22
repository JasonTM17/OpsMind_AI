package ai.opsmind.platform.testing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public final class PostgresTenantFixtures {

    public static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    public static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    public static final UUID USER_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    public static final UUID USER_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    public static final UUID PROJECT_A = UUID.fromString("aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    public static final UUID PROJECT_B = UUID.fromString("bbbbbba1-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    public static final UUID DISPATCHER_A = UUID.fromString("d15a0001-d15a-415a-815a-d15a00000001");
    public static final UUID DISPATCHER_B = UUID.fromString("d15b0001-d15b-415b-815b-d15b00000001");

    private PostgresTenantFixtures() {
    }

    public static void seed(PostgresIntegrationEnvironment environment) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
            environment.jdbcUrl(),
            environment.adminUser(),
            environment.adminPassword()
        ); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("""
                INSERT INTO organizations (id, slug, name)
                VALUES
                    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'phase3-a', 'Phase 3 Tenant A'),
                    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'phase3-b', 'Phase 3 Tenant B')
                ON CONFLICT (id) DO NOTHING
                """);
            statement.executeUpdate("""
                INSERT INTO platform_users (id, issuer, subject, display_name)
                VALUES
                    ('11111111-1111-4111-8111-111111111111',
                     'https://idp.example.test/opsmind', 'phase3-operator-a', 'Phase 3 Operator A'),
                    ('22222222-2222-4222-8222-222222222222',
                     'https://idp.example.test/opsmind', 'phase3-operator-b', 'Phase 3 Operator B')
                ON CONFLICT (id) DO NOTHING
                """);
            statement.executeUpdate("""
                INSERT INTO organization_memberships (organization_id, user_id, role)
                VALUES
                    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
                     '11111111-1111-4111-8111-111111111111', 'SRE'),
                    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
                     '22222222-2222-4222-8222-222222222222', 'SRE')
                ON CONFLICT (organization_id, user_id) DO UPDATE
                    SET role = EXCLUDED.role, status = 'active'
                """);
            statement.executeUpdate("""
                INSERT INTO projects (id, organization_id, slug, name)
                VALUES
                    ('aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
                     'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'project-a', 'Project A'),
                    ('bbbbbba1-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
                     'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'project-b', 'Project B')
                ON CONFLICT (id) DO NOTHING
                """);
            statement.executeUpdate("""
                INSERT INTO project_memberships (
                    organization_id, project_id, user_id, role
                ) VALUES
                    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
                     'aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
                     '11111111-1111-4111-8111-111111111111', 'SRE'),
                    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
                     'bbbbbba1-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
                     '22222222-2222-4222-8222-222222222222', 'SRE')
                ON CONFLICT (project_id, user_id) DO UPDATE
                    SET role = EXCLUDED.role, status = 'active'
                """);
            statement.executeUpdate("""
                INSERT INTO service_accounts (
                    id, organization_id, name, credential_ref,
                    allowed_audiences, allowed_scopes, database_principal
                ) VALUES
                    ('d15a0001-d15a-415a-815a-d15a00000001',
                     'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'outbox-dispatcher',
                     'secret-manager://phase3/tenant-a/dispatcher',
                     '["opsmind-outbox-dispatcher"]'::jsonb,
                     '["outbox:dispatch"]'::jsonb, 'opsmind_dispatcher'),
                    ('d15b0001-d15b-415b-815b-d15b00000001',
                     'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'outbox-dispatcher',
                     'secret-manager://phase3/tenant-b/dispatcher',
                     '["opsmind-outbox-dispatcher"]'::jsonb,
                     '["outbox:dispatch"]'::jsonb, 'opsmind_dispatcher')
                ON CONFLICT (id) DO NOTHING
                """);
            connection.commit();
        }
    }
}
