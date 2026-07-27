package ai.opsmind.toolgateway.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

/** Catalog-only readiness checks for the complete Tool Gateway isolation posture. */
final class GatewayIsolationReadinessSql {

    private static final String EXPECTED_POLICY_EXPRESSION =
        "tenant_id=tool_gateway.current_tenant_id"
            + "ANDproject_id=tool_gateway.current_project_id";

    private GatewayIsolationReadinessSql() {
    }

    static boolean nonceStoreReady(JdbcTemplate jdbc) {
        Boolean ready = jdbc.queryForObject(
            "SELECT current_user = 'opsmind_tool_gateway' "
                + "AND has_schema_privilege(current_user, 'tool_gateway', 'USAGE') "
                + "AND to_regclass('tool_gateway.capability_nonce_claims') IS NOT NULL "
                + "AND has_table_privilege(current_user, "
                + "'tool_gateway.capability_nonce_claims', 'SELECT') "
                + "AND has_table_privilege(current_user, "
                + "'tool_gateway.capability_nonce_claims', 'INSERT') "
                + "AND has_table_privilege(current_user, "
                + "'tool_gateway.capability_nonce_claims', 'DELETE') "
                + "AND NOT COALESCE(("
                + "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user"
                + "), true)",
            Boolean.class
        );
        return Boolean.TRUE.equals(ready);
    }

    static boolean receiptStoreReady(JdbcTemplate jdbc) {
        Boolean ready = jdbc.queryForObject(
            "SELECT current_user = 'opsmind_tool_gateway' "
                + "AND has_schema_privilege(current_user, 'tool_gateway', 'USAGE') "
                + "AND to_regclass('tool_gateway.execution_receipts') IS NOT NULL "
                + "AND has_table_privilege(current_user, "
                + "'tool_gateway.execution_receipts', 'SELECT') "
                + "AND has_table_privilege(current_user, "
                + "'tool_gateway.execution_receipts', 'INSERT') "
                + "AND has_table_privilege(current_user, "
                + "'tool_gateway.execution_receipts', 'UPDATE') "
                + "AND to_regprocedure("
                + "'tool_gateway.set_tenant_context(uuid,uuid)') IS NOT NULL "
                + "AND to_regprocedure("
                + "'tool_gateway.current_tenant_id()') IS NOT NULL "
                + "AND to_regprocedure("
                + "'tool_gateway.current_project_id()') IS NOT NULL "
                + "AND has_function_privilege(current_user, "
                + "'tool_gateway.set_tenant_context(uuid,uuid)', 'EXECUTE') "
                + "AND has_function_privilege(current_user, "
                + "'tool_gateway.current_tenant_id()', 'EXECUTE') "
                + "AND has_function_privilege(current_user, "
                + "'tool_gateway.current_project_id()', 'EXECUTE') "
                + "AND EXISTS ("
                + "SELECT 1 FROM pg_class table_state "
                + "JOIN pg_namespace schema_state "
                + "ON schema_state.oid = table_state.relnamespace "
                + "WHERE schema_state.nspname = 'tool_gateway' "
                + "AND table_state.relname = 'execution_receipts' "
                + "AND pg_get_userbyid(table_state.relowner) <> current_user "
                + "AND table_state.relrowsecurity "
                + "AND table_state.relforcerowsecurity) "
                + "AND NOT COALESCE(("
                + "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user"
                + "), true)",
            Boolean.class
        );
        return Boolean.TRUE.equals(ready) && policyReady(
            jdbc,
            "execution_receipts",
            "execution_receipts_tenant_project_isolation"
        );
    }

    static boolean auditStoreReady(JdbcTemplate jdbc) {
        Boolean ready = jdbc.queryForObject(
            "SELECT current_user = 'opsmind_tool_gateway' "
                + "AND has_schema_privilege(current_user, 'tool_gateway', 'USAGE') "
                + "AND to_regclass('tool_gateway.tool_audit_events') IS NOT NULL "
                + "AND to_regclass("
                + "'tool_gateway.unverified_tool_audit_events') IS NOT NULL "
                + "AND to_regprocedure("
                + "'tool_gateway.set_tenant_context(uuid,uuid)') IS NOT NULL "
                + "AND to_regprocedure("
                + "'tool_gateway.current_tenant_id()') IS NOT NULL "
                + "AND to_regprocedure("
                + "'tool_gateway.current_project_id()') IS NOT NULL "
                + "AND has_function_privilege(current_user, "
                + "'tool_gateway.set_tenant_context(uuid,uuid)', 'EXECUTE') "
                + "AND has_function_privilege(current_user, "
                + "'tool_gateway.current_tenant_id()', 'EXECUTE') "
                + "AND has_function_privilege(current_user, "
                + "'tool_gateway.current_project_id()', 'EXECUTE') "
                + "AND has_table_privilege(current_user, "
                + "'tool_gateway.tool_audit_events', 'INSERT') "
                + "AND NOT has_table_privilege(current_user, "
                + "'tool_gateway.tool_audit_events', "
                + "'SELECT,UPDATE,DELETE,TRUNCATE') "
                + "AND has_table_privilege(current_user, "
                + "'tool_gateway.unverified_tool_audit_events', 'INSERT') "
                + "AND NOT has_table_privilege(current_user, "
                + "'tool_gateway.unverified_tool_audit_events', "
                + "'SELECT,UPDATE,DELETE,TRUNCATE') "
                + "AND EXISTS ("
                + "SELECT 1 FROM pg_class table_state "
                + "JOIN pg_namespace schema_state "
                + "ON schema_state.oid = table_state.relnamespace "
                + "WHERE schema_state.nspname = 'tool_gateway' "
                + "AND table_state.relname = 'tool_audit_events' "
                + "AND pg_get_userbyid(table_state.relowner) <> current_user "
                + "AND table_state.relrowsecurity "
                + "AND table_state.relforcerowsecurity) "
                + "AND NOT COALESCE(("
                + "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user"
                + "), true)",
            Boolean.class
        );
        return Boolean.TRUE.equals(ready) && policyReady(
            jdbc,
            "tool_audit_events",
            "tool_audit_events_tenant_project_isolation"
        );
    }

    private static boolean policyReady(
        JdbcTemplate jdbc,
        String tableName,
        String policyName
    ) {
        Boolean ready = jdbc.queryForObject(
            "SELECT count(*) = 1 AND COALESCE(bool_and("
                + "policyname = ? "
                + "AND permissive = 'PERMISSIVE' "
                + "AND roles = ARRAY['public']::name[] "
                + "AND cmd = 'ALL' "
                + "AND regexp_replace(qual, '[[:space:]()]', '', 'g') = ? "
                + "AND regexp_replace(with_check, '[[:space:]()]', '', 'g') = ?"
                + "), false) "
                + "FROM pg_policies "
                + "WHERE schemaname = 'tool_gateway' AND tablename = ?",
            Boolean.class,
            policyName,
            EXPECTED_POLICY_EXPRESSION,
            EXPECTED_POLICY_EXPRESSION,
            tableName
        );
        return Boolean.TRUE.equals(ready);
    }
}
