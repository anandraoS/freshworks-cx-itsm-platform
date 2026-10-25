import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.TreeMap;

/**
 * H2-ONLY query-plan demo (NOT PostgreSQL). Loads the portable Flyway migrations V1..V15 from this repo into an in-memory
 * H2 database (PostgreSQL mode, same URL options as application-local.yml), generates 3 tenants (50 000 / 20 000 / 5 000 tickets)
 * and prints EXPLAIN ANALYZE plan lines (index chosen and scanCount = rows examined).
 *
 *   java -cp <h2-2.2.224.jar> perf/sql/H2Explain.java <repo-root>
 *
 * Wall-clock timings are deliberately NOT printed: at this size they are dominated by statement parsing and H2's result cache and
 * were not reliable. scanCount is the reproducible signal. Do not read these plans as PostgreSQL plans.
 */
public class H2Explain {
    static Connection c;

    public static void main(String[] a) throws Exception {
        Path repo = Path.of(a.length > 0 ? a[0] : ".");
        c = DriverManager.getConnection("jdbc:h2:mem:x;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
        TreeMap<Integer, Path> migrations = new TreeMap<>();
        try (var ds = Files.newDirectoryStream(repo.resolve("core-service/src/main/resources/db/migration"))) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                int v = Integer.parseInt(n.substring(1, n.indexOf("__")));
                if (v <= 15) migrations.put(v, p);
            }
        }
        for (Path p : migrations.values()) {
            for (String stmt : Files.readString(p).split(";\\s*(\\r?\\n|$)")) {
                String s = stmt.replaceAll("(?m)^\\s*--.*$", "").trim();
                if (!s.isEmpty()) c.createStatement().execute(s);
            }
        }
        c.createStatement().execute("insert into organization(id,slug,name,created_at) select random_uuid(),'t'||x,'T'||x,now() from system_range(1,3) r(x)");
        c.createStatement().execute("create table tmp_org as select id, row_number() over (order by slug) rn from organization");
        c.createStatement().execute("insert into app_user(id,tenant_id,email,display_name,password_hash,roles,created_at) select random_uuid(), id,'u@'||rn||'.test','U','x','AGENT',now() from tmp_org");
        String ins = "insert into ticket(id,tenant_id,number,type,title,description,status,priority,severity,requester_id,created_at,updated_at,sla_status,tags) "
                + "select random_uuid(), o.id, x, 'SUPPORT', 'Printer ' || mod(x,97) || ' jammed ticket ' || x, 'desc ' || x, "
                + "case mod(x,7) when 0 then 'NEW' when 1 then 'OPEN' when 2 then 'PENDING' when 3 then 'RESOLVED' when 4 then 'CLOSED' when 5 then 'OPEN' else 'ON_HOLD' end, "
                + "case mod(x,4) when 0 then 'LOW' when 1 then 'MEDIUM' when 2 then 'HIGH' else 'URGENT' end, 'MINOR', u.id, "
                + "dateadd('MINUTE', -x, now()), now(), 'ON_TRACK', ',' "
                + "from system_range(1, ?) r(x), tmp_org o join app_user u on u.tenant_id=o.id where o.rn = ?";
        int[] sizes = {50000, 20000, 5000};
        for (int i = 0; i < 3; i++) {
            try (var ps = c.prepareStatement(ins)) { ps.setInt(1, sizes[i]); ps.setInt(2, i + 1); ps.executeUpdate(); }
        }
        c.createStatement().execute("create table ticket_noidx as select * from ticket"); // same rows, no indexes
        c.createStatement().execute("analyze");
        String t1;
        try (var rs = c.createStatement().executeQuery("select id from tmp_org where rn=1")) { rs.next(); t1 = rs.getString(1); }
        System.out.println("H2 " + c.getMetaData().getDatabaseProductVersion() + "; ticket rows: 75000 (tenant 1 = 50000)");

        String q1 = "select * from %s where tenant_id='" + t1 + "' and status='OPEN' order by created_at desc, id desc limit 20";
        plan("Q1 ticket list, tenant + status filter, existing indexes", String.format(q1, "ticket"));
        plan("Q1 same query, table copy WITHOUT indexes", String.format(q1, "ticket_noidx"));
        plan("Q2 status filter WITHOUT the tenant predicate", "select * from ticket where status='OPEN' order by created_at desc, id desc limit 20");
        plan("Q3 contains-search lower(title) like '%...%' (the portable JPQL form)",
                "select count(*) from ticket where tenant_id='" + t1 + "' and lower(title) like '%jammed ticket 4999%'");
        plan("Q4 deep OFFSET page (offset 40000)",
                "select * from ticket where tenant_id='" + t1 + "' order by created_at desc, id desc limit 20 offset 40000");
        String ts, id;
        try (var rs = c.createStatement().executeQuery("select created_at, id from ticket where tenant_id='" + t1 + "' order by created_at desc, id desc limit 1 offset 40000")) {
            rs.next(); ts = rs.getString(1); id = rs.getString(2);
        }
        plan("Q4b keyset page at the same position: (created_at,id) < (last seen)",
                "select * from ticket where tenant_id='" + t1 + "' and (created_at, id) < ('" + ts + "', '" + id + "') order by created_at desc, id desc limit 20");
        plan("Q5 open tickets by priority (dashboard count)",
                "select priority, count(*) from ticket where tenant_id='" + t1 + "' and status not in ('RESOLVED','CLOSED') group by priority");
        c.createStatement().execute("create index ix_candidate on ticket (tenant_id, status, created_at desc, id desc)");
        c.createStatement().execute("analyze");
        plan("Q1 again after CANDIDATE index (tenant_id, status, created_at desc, id desc) - not part of the migrations", String.format(q1, "ticket"));
    }

    static void plan(String title, String sql) throws SQLException {
        System.out.println("\n=== " + title + "\n" + sql);
        try (ResultSet rs = c.createStatement().executeQuery("explain analyze " + sql)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) sb.append(rs.getString(1)).append('\n');
            for (String l : sb.toString().split("\\n")) {
                String t = l.trim();
                if (t.startsWith("/*")) System.out.println("  " + t.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>"));
            }
        }
    }
}
