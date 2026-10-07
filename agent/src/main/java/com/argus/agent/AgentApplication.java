package com.argus.agent;

/** Argus 节点 Agent 启动入口，只依赖 JDK 17 运行时。 */
public final class AgentApplication {
    private AgentApplication() { }

    public static void main(String[] args) throws Exception {
        AgentConfig config = AgentConfig.load(args);
        new AgentServer(config).start();
    }
}
