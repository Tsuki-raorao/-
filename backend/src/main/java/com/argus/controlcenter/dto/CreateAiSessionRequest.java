package com.argus.controlcenter.dto;
import jakarta.validation.constraints.Size;
public class CreateAiSessionRequest {
    @Size(max=200) private String title;
    public String getTitle(){return title;} public void setTitle(String value){title=value;}
}
