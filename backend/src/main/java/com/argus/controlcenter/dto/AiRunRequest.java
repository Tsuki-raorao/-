package com.argus.controlcenter.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public class AiRunRequest {
    @NotBlank @Size(max=4000) private String question;
    public String getQuestion(){return question;} public void setQuestion(String value){question=value;}
}
