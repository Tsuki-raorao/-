package com.argus.controlcenter.service;
import com.argus.controlcenter.dto.CreateAiKnowledgeRequest;
import com.argus.controlcenter.identity.*;
import com.argus.controlcenter.repository.AiKnowledgeRepository;
import org.springframework.stereotype.Service;
import java.util.*;
@Service public class AiKnowledgeService {
 private final AiKnowledgeRepository repository; private final ProjectAuthorization authorization;
 public AiKnowledgeService(AiKnowledgeRepository repository,ProjectAuthorization authorization){this.repository=repository;this.authorization=authorization;}
 public String create(String project,CreateAiKnowledgeRequest body,CurrentActor actor){String p=scope(project);authorization.requirePlatformAdminForUpdate(actor,p);return repository.save(p,body.getTitle(),body.getSourceUri(),body.getVersion(),body.getContent());}
 public List<Map<String,Object>> search(String project,String query,CurrentActor actor){String p=scope(project);authorization.require(actor,p,ProjectPermission.RESOURCE_READ);return repository.search(p,query,20);}
 private String scope(String p){if(p==null||p.isBlank()){if(authorization.isIdentityMode())throw new IdentityAuthorizationException("PROJECT_REQUIRED",400);return IdentityConstants.LEGACY_PROJECT_ID;}return p;}
}
