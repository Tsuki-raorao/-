import org.flywaydb.core.Flyway;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Upgrade a restored production copy; prove every preexisting business table is unchanged. */
public class MigrationSmoke {
    static Map<String,Object> fingerprint(Connection connection,String table) throws Exception {
        if(!table.matches("[A-Za-z0-9_]+"))throw new IllegalArgumentException("invalid table");
        SortedMap<Integer,String> primary=new TreeMap<>();
        try(var keys=connection.getMetaData().getPrimaryKeys(connection.getCatalog(),null,table)){
            while(keys.next())primary.put(keys.getInt("KEY_SEQ"),keys.getString("COLUMN_NAME"));
        }
        if(primary.isEmpty())throw new IllegalStateException("table has no stable primary key");
        String order=String.join(",",primary.values().stream().map(name->"`"+name+"`").toList());
        var digest=MessageDigest.getInstance("SHA-256");long count=0;
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM `"+table+"` ORDER BY "+order)){
            int columns=rows.getMetaData().getColumnCount();
            while(rows.next()){
                for(int i=1;i<=columns;i++){
                    byte[] value=rows.getBytes(i);digest.update(ByteBuffer.allocate(4).putInt(value==null?-1:value.length).array());if(value!=null)digest.update(value);
                }count++;
            }
        }
        return Map.of("rows",count,"sha256",HexFormat.of().formatHex(digest.digest()));
    }
    public static void main(String[] args) throws Exception {
        if(!"argus_test_release".equals(System.getenv("MYSQL_DATABASE")))throw new IllegalArgumentException("isolated production-copy database required");
        String url="jdbc:mysql://127.0.0.1:23306/argus_test_release?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC";
        String user=System.getenv("MYSQL_USER"),password=System.getenv("MYSQL_PASSWORD");
        var flyway=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load();
        String before=flyway.info().current().getVersion().getVersion();
        if(!before.equals("9"))throw new IllegalStateException("expected untouched V9 snapshot");
        Map<String,Map<String,Object>> facts=new TreeMap<>();
        try(var c=DriverManager.getConnection(url,user,password);var statement=c.createStatement();var rows=statement.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' ORDER BY TABLE_NAME")){
            List<String> tables=new ArrayList<>();while(rows.next())tables.add(rows.getString(1));
            for(String table:tables)facts.put(table,fingerprint(c,table));
        }
        var migrated=flyway.migrate();
        try(var c=DriverManager.getConnection(url,user,password)){
            for(var entry:facts.entrySet())if(!entry.getValue().equals(fingerprint(c,entry.getKey())))throw new IllegalStateException("existing business rows changed: "+entry.getKey());
        }
        String after=flyway.info().current().getVersion().getVersion();if(!after.equals("12")||migrated.migrationsExecuted!=3)throw new IllegalStateException("unexpected migration result");
        var report=Map.of("scope","ISOLATED_RESTORED_PRODUCTION_COPY","beforeVersion",before,"afterVersion",after,"migrationsExecuted",migrated.migrationsExecuted,"allExistingBusinessRowsUnchanged",true,"businessTables",facts);
        Files.writeString(Path.of("/opt/argus/acceptance-20261019/production-copy-migration.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Production-copy migration passed; "+facts.size()+" existing business table fingerprints unchanged");
    }
}
