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
 public String save(String project,String title,String source,String version,String content){String id=UUID.randomUUID().toString(),hash=sha(content);Instant now=Instant.now();jdbc.update("INSERT INTO ai_knowledge_documents(id,project_id,title,source_uri,version,status,content_hash,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',?,?,?)",id,project,title,source,version,hash,Timestamp.from(now),Timestamp.from(now));int no=0;for(int start=0;start<content.length();start+=1800){String chunk=content.substring(start,Math.min(content.length(),start+1800));jdbc.update("INSERT INTO ai_knowledge_chunks(id,document_id,project_id,chunk_no,content,content_hash,created_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,project,no++,chunk,sha(chunk),Timestamp.from(now));}return id;}
 public List<Map<String,Object>> search(String project,String query,int limit){String q=query==null?"":query.trim();if(q.isBlank())return List.of();String[] terms=q.replaceAll("[^\\p{L}\\p{N}_-]"," ").trim().split("\\s+");String term=terms.length==0?"":terms[0];int size=Math.max(1,Math.min(limit,20));return jdbc.queryForList("SELECT d.id AS document_id,d.title,d.source_uri,d.version,c.id AS chunk_id,c.chunk_no,c.content FROM ai_knowledge_documents d JOIN ai_knowledge_chunks c ON c.document_id=d.id WHERE d.project_id=? AND d.status='ACTIVE' AND LOWER(c.content) LIKE LOWER(?) ORDER BY d.updated_at DESC,c.chunk_no LIMIT ?",project,"%"+term+"%",size);}
 private String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
