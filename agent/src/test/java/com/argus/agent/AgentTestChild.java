package com.argus.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/** 测试专用子进程，不执行 shell、Docker 或网络操作。 */
public final class AgentTestChild {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "large" -> {
                byte[] block = new byte[8192];
                java.util.Arrays.fill(block, (byte) 'x');
                for (int i = 0; i < 100; i++) System.out.write(block);
            }
            case "fail" -> { System.err.print("PRIVATE_TEST_MARKER"); System.exit(17); }
            case "sleep" -> {
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(30000);
            }
            case "tree-limit", "tree-timeout" -> {
                // 保留父、子两层：子进程继承输出管道，并持续存活，验证清理不只结束包装进程。
                Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp", System.getProperty("java.class.path"),
                        AgentTestChild.class.getName(), "sleep", args[2]).inheritIO().start();
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                long readyDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (!Files.exists(Path.of(args[2]))) {
                    if (!child.isAlive() || System.nanoTime() > readyDeadline) throw new IllegalStateException("test child did not start");
                    Thread.sleep(10);
                }
                if (args[0].equals("tree-limit")) {
                    byte[] block = new byte[8192];
                    java.util.Arrays.fill(block, (byte) 'x');
                    for (int i = 0; i < 100; i++) System.out.write(block);
                    System.out.flush();
                }
                Thread.sleep(30000);
            }
            case "require-token" -> {
                try {
                    AgentConfig.load(new String[]{"--config=" + Path.of(System.getProperty("java.io.tmpdir"), "nonexistent-test-config")});
                    throw new AssertionError("real mode started without token");
                } catch (IllegalArgumentException rejected) {
                    if (!rejected.getMessage().contains("required when executor.mock=false")) throw rejected;
                    System.out.print("AUTH_GUARD_OK");
                }
            }
            default -> throw new IllegalArgumentException("unknown test mode");
        }
    }
}
