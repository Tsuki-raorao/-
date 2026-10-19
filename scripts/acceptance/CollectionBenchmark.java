import com.argus.controlcenter.ArgusControlCenterApplication;
import com.argus.controlcenter.service.AgentSnapshotSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real collector + real MySQL, simulated HTTP Agents. No Docker execution. */
public class CollectionBenchmark {
    public static void main(String[] args) throws Exception {
        String label=args[0];
        if(!Set.of("before","after").contains(label) || !"argus_test_collection".equals(System.getenv("MYSQL_DATABASE")))
            throw new IllegalArgumentException("dedicated collection test database required");
        var json=new ObjectMapper();var active=new AtomicInteger();var peak=new AtomicInteger();var requests=new AtomicInteger();
        var threads=Executors.newFixedThreadPool(32,r->{var t=new Thread(r,"simulated-agent");t.setDaemon(true);return t;});
        List<HttpServer> servers=new ArrayList<>();
        try {
            for(int i=0;i<100;i++){
                var server=HttpServer.create(new InetSocketAddress("127.0.0.1",18100+i),8);
                server.setExecutor(threads);
                server.createContext("/api/agent/",exchange->{
                    byte[] body;int status=200;boolean entered=false;
                    try{
                        if(!("Bearer "+System.getenv("ARGUS_AGENT_AUTH_TOKEN")).equals(exchange.getRequestHeaders().getFirst("Authorization"))){status=401;body="{}".getBytes();}
                        else{
                            peak.accumulateAndGet(active.incrementAndGet(),Math::max);entered=true;requests.incrementAndGet();
                            Thread.sleep(250);
                            String at=Instant.now().toString();String path=exchange.getRequestURI().getPath();
                            if(path.endsWith("/health"))body=json.writeValueAsBytes(Map.of("status","UP","protocolVersion","1.1","dataSource","HOST","metricsStatus","AVAILABLE","sampledAt",at,"hostCpuPercent",10,"hostMemoryBytes",1024,"hostMemoryTotalBytes",4096));
                            else if(path.endsWith("/instances")){
                                List<Map<String,Object>> items=new ArrayList<>();
                                for(int n=0;n<2;n++)items.add(Map.of("instanceId","fixture-"+n,"status","RUNNING","dataSource","DOCKER","metricsStatus","AVAILABLE","sampledAt",at,"cpuPercent",1,"memoryBytes",128));
                                body=json.writeValueAsBytes(items);
                            }else{status=404;body="{}".getBytes();}
                        }
                        exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);
                    }catch(Exception failure){/* client timeout is measured as a failed node */}
                    finally{if(entered)active.decrementAndGet();exchange.close();}
                });server.start();servers.add(server);
            }
            try(var context=new SpringApplicationBuilder(ArgusControlCenterApplication.class).run()){
                var jdbc=context.getBean(JdbcTemplate.class);var collector=context.getBean(AgentSnapshotSyncService.class);
                if(jdbc.queryForObject("SELECT COUNT(*) FROM tasks",Long.class)!=0 || jdbc.queryForObject("SELECT COUNT(*) FROM nodes WHERE id NOT LIKE 'collection-%'",Long.class)!=0)
                    throw new IllegalStateException("refuse to replace non-fixture data");
                List<Map<String,Object>> rounds=new ArrayList<>();
                for(int count:new int[]{10,50,100}){
                    jdbc.update("DELETE FROM instances WHERE node_id LIKE 'collection-%'");jdbc.update("DELETE FROM nodes WHERE id LIKE 'collection-%'");
                    for(int i=0;i<count;i++)jdbc.update("INSERT INTO nodes(id,name,address,status,project_id) VALUES(?,?,?,'UNKNOWN',?)",String.format("collection-%03d",i),"Collection "+i,"http://127.0.0.1:"+(18100+i),"00000000-0000-0000-0000-000000000001");
                    requests.set(0);peak.set(0);long started=System.nanoTime();collector.syncAll();long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);Instant end=Instant.now();
                    var values=jdbc.query("SELECT last_successful_sync_at,sampled_at,sync_status FROM nodes",(rs,row)->Map.of("ok","OK".equals(rs.getString(3)),"syncAge",rs.getTimestamp(1)==null?-1L:Duration.between(rs.getTimestamp(1).toInstant(),end).toMillis(),"sampleAge",rs.getTimestamp(2)==null?-1L:Duration.between(rs.getTimestamp(2).toInstant(),end).toMillis()));
                    long successes=values.stream().filter(v->Boolean.TRUE.equals(v.get("ok"))).count();
                    Map<String,Object> result=new LinkedHashMap<>();result.put("nodes",count);result.put("instancesPerNode",2);result.put("perEndpointDelayMs",250);result.put("roundMs",elapsed);result.put("successfulNodes",successes);result.put("persistedInstances",jdbc.queryForObject("SELECT COUNT(*) FROM instances",Long.class));result.put("httpRequests",requests.get());result.put("peakConcurrentHttp",peak.get());result.put("oldestFullSyncAgeMs",values.stream().mapToLong(v->(Long)v.get("syncAge")).max().orElse(-1));result.put("oldestSampleAgeMs",values.stream().mapToLong(v->(Long)v.get("sampleAge")).max().orElse(-1));result.put("roundBudgetMs",30000);result.put("targetPassed",successes==count&&elapsed<=30000);
                    rounds.add(result);System.out.println("COLLECTION_ROUND "+json.writeValueAsString(result));
                }
                Map<String,Object> report=Map.of("scope","ISOLATED_CLOUD_SIMULATED_AGENTS_REAL_COLLECTOR_MYSQL","label",label,"rounds",rounds);
                Files.writeString(Path.of("/opt/argus/acceptance-20261019/collection-"+label+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report),StandardCharsets.UTF_8);
            }
        }finally{servers.forEach(server->server.stop(0));threads.shutdownNow();}
    }
}
