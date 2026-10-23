package com.argus.controlcenter.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public class CreateAiKnowledgeRequest { @NotBlank @Size(max=200) private String title; @NotBlank @Size(max=500) private String sourceUri; @NotBlank @Size(max=64) private String version; @NotBlank @Size(max=200000) private String content;
 public String getTitle(){return title;} public void setTitle(String v){title=v;} public String getSourceUri(){return sourceUri;} public void setSourceUri(String v){sourceUri=v;} public String getVersion(){return version;} public void setVersion(String v){version=v;} public String getContent(){return content;} public void setContent(String v){content=v;}}
