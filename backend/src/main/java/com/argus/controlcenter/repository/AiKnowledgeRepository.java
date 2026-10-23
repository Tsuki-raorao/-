package com.argus.controlcenter.repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
@Repository public class AiKnowledgeRepository {
 private final JdbcTemplate jdbc; public AiKnowledgeRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public String save(String project,String title,String source,String version,String content){String id=UUID.randomUUID().toString(),hash=sha(content);Instant now=Instant.now();jdbc.update("UPDATE ai_knowledge_documents SET status='DEPRECATED',updated_at=? WHERE project_id=? AND source_uri=? AND status='ACTIVE'",Timestamp.from(now),project,source);jdbc.update("INSERT INTO ai_knowledge_documents(id,project_id,title,source_uri,version,status,content_hash,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',?,?,?)",id,project,title,source,version,hash,Timestamp.from(now),Timestamp.from(now));int no=0;for(int start=0;start<content.length();start+=1800){String chunk=content.substring(start,Math.min(content.length(),start+1800));jdbc.update("INSERT INTO ai_knowledge_chunks(id,document_id,project_id,chunk_no,content,content_hash,created_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,project,no++,chunk,sha(chunk),Timestamp.from(now));}return id;}
 public List<Map<String,Object>> search(String project,String query,int limit){String q=query==null?"":query.trim();if(q.isBlank())return List.of();String[] terms=Arrays.stream(q.replaceAll("[^\\p{L}\\p{N}_-]"," ").trim().split("\\s+")).filter(s->s.length()>1).map(String::toLowerCase).distinct().toArray(String[]::new);if(terms.length==0)return List.of();List<Map<String,Object>> rows=jdbc.queryForList("SELECT d.id AS document_id,d.title,d.source_uri,d.version,c.id AS chunk_id,c.chunk_no,c.content FROM ai_knowledge_documents d JOIN ai_knowledge_chunks c ON c.document_id=d.id WHERE d.project_id=? AND d.status='ACTIVE' ORDER BY d.updated_at DESC,c.chunk_no",project);List<Map<String,Object>> matches=new ArrayList<>();for(Map<String,Object> row:rows){String text=String.valueOf(row.get("content")).toLowerCase();long score=Arrays.stream(terms).filter(text::contains).count();if(score>0){row.put("score",score);matches.add(row);}}matches.sort((a,b)->Long.compare(((Number)b.get("score")).longValue(),((Number)a.get("score")).longValue()));return matches.subList(0,Math.min(Math.max(1,Math.min(limit,20)),matches.size()));}
 private String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
