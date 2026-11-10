package io.agentscope.platform.server.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * 文件名清洗是安全边界：object key 由服务端生成，但文件名来自模型/沙箱，
 * 不清洗就能构造路径穿越。
 */
class ArtifactNameTest {

    @Test
    void stripsPathTraversal() {
        assertEquals("passwd", ArtifactService.safeName("../../etc/passwd"));
        assertEquals("evil.txt", ArtifactService.safeName("/tmp/../../evil.txt"));
    }

    @Test
    void keepsChineseAndReplacesUnsafeChars() {
        assertEquals("报告_2026.csv", ArtifactService.safeName("报告 2026.csv"));
    }

    @Test
    void fallsBackWhenEmpty() {
        assertEquals("artifact", ArtifactService.safeName(null));
        assertEquals("artifact", ArtifactService.safeName("   "));
        assertFalse(ArtifactService.safeName("...").contains("/"));
    }
}
