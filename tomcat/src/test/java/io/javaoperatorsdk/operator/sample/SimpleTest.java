package io.javaoperatorsdk.operator.sample;

import org.junit.Assert;
import org.junit.Test;
// this is for reference
// on target regular test should be set with io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
public class SimpleTest {
    @Test
    public void test() {
        Assert.assertSame("foo","foo");
    }
}
