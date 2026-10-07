package com.argus.controlcenter.dto;

import jakarta.validation.constraints.NotBlank;

/** 创建服务器节点所需的最小信息。 */
public class CreateNodeRequest {
    /** 用户可读节点名称。 */
    @NotBlank private String name;
    /** 节点地址，例如 Agent 的 HTTP 地址。 */
    @NotBlank private String address;
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
}
